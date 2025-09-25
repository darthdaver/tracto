import csv, time, logging, argparse, torch, transformers, os, json, io
import torch.distributed as dist
from torch.utils.data import IterableDataset, get_worker_info
from transformers import Trainer, EarlyStoppingCallback
from transformers.trainer_utils import is_main_process
from transformers import BitsAndBytesConfig
from dataclasses import dataclass, field
from typing import Dict, Optional, Sequence, List, Iterator
from peft import prepare_model_for_kbit_training
from peft import get_peft_model, LoraConfig


IGNORE_INDEX = -100 #default ignore_index = 100 in transformers
logging.basicConfig(level=logging.DEBUG)


@dataclass
class ModelArguments:
    model_name_or_path: Optional[str] = field(default="Qwen/Qwen2.5-Coder-7B-Instruct")
    use_flash_attention: bool = field(default=False, metadata={"help": "Whether to use Flash Attention."})

@dataclass
class DataArguments:
    train_file: Optional[str] = field(default=os.path.join(os.path.dirname(__file__), "..", "output", "split-dataset", "train_projects.csv"))
    eval_file: Optional[str] = field(default=os.path.join(os.path.dirname(__file__), "..", "output", "split-dataset", "val_projects.csv"))
    data_dir: Optional[str] = field(default=os.path.join(os.path.dirname(__file__), "..", "prompt", "output", "1", "prompt-binary"))


@dataclass
class TrainingArguments(transformers.TrainingArguments):
    output_dir: Optional[str] = field(default=os.path.join(os.path.dirname(__file__), "..", "output", "finetuning"))
    cache_dir: Optional[str] = field(default=None)
    optim: str = field(default="adamw_torch")
    model_max_length: int = field(
        default=32768,
        metadata={"help": "Maximum sequence length. Sequences will be right padded (and possibly truncated)."},
    )
    num_train_epochs: int = field(default=5)
    truncate_source: bool = field(default=False)
    use_peft: bool = field(default=True)
    peft_config_path: str = field(default=None)
    deepspeed: Optional[str] = None #"./tracto/scripts/neural-module/ds_config.json"
    dataloader_num_workers = 4
    dataloader_persistent_workers = True
    dataloader_pin_memory = True
    gradient_checkpointing: bool = True
    bf16: bool = True  # A100
    fp16: bool = False  # avoid mixed bf16/fp16 confusion
    per_device_train_batch_size: int = 1
    per_device_eval_batch_size: int = 1
    gradient_accumulation_steps: int = 16  # raise to hit target tokens/step
    group_by_length: bool = True
    ddp_find_unused_parameters: bool = False
    eval_accumulation_steps: int = 1  # prevents CPU RAM blowup
    prediction_loss_only: bool = True  # don’t store logits by default
    max_grad_norm: float = 0.3
    report_to: Optional[str] = "none"
    torch_compile: bool = False  # leave off unless you’ve profiled it

@dataclass
class DataCollatorForSupervisedDataset:
    tokenizer: transformers.PreTrainedTokenizer
    pad_to_multiple_of: int = 8

    def __call__(self, instances: Sequence[Dict]) -> Dict[str, torch.Tensor]:
        input_ids = [inst["input_ids"] for inst in instances]
        labels    = [inst["labels"]    for inst in instances]

        input_ids = torch.nn.utils.rnn.pad_sequence(
            input_ids, batch_first=True, padding_value=self.tokenizer.pad_token_id
        )
        labels = torch.nn.utils.rnn.pad_sequence(
            labels, batch_first=True, padding_value=IGNORE_INDEX
        )
        # pad to multiple of 8 if needed
        if self.pad_to_multiple_of:
            m = self.pad_to_multiple_of
            to = ((input_ids.size(1) + m - 1) // m) * m
            if to != input_ids.size(1):
                pad = to - input_ids.size(1)
                input_ids = torch.nn.functional.pad(input_ids, (0, pad), value=self.tokenizer.pad_token_id)
                labels    = torch.nn.functional.pad(labels,    (0, pad), value=IGNORE_INDEX)
        return {
            "input_ids": input_ids,
            "labels": labels,
            "attention_mask": input_ids.ne(self.tokenizer.pad_token_id),
        }

class JsonlIterable(IterableDataset):
    def __init__(
        self,
        files: List[str],
        tokenizer,
        max_length: int,
        only_last_turn_loss: bool = True,
        drop_long: bool = False,   # if False, truncate to max_length
    ):
        self.files = files
        self.tok = tokenizer
        self.maxlen = max_length
        self.only_last = only_last_turn_loss
        self.drop_long = drop_long
        # cache special IDs
        self.im_start_id = self.tok("<|im_start|>").input_ids[0]
        self.im_end_id   = self.tok("<|im_end|>").input_ids[0]
        self.nl_id       = self.tok("\n", add_special_tokens=False).input_ids[-1:]
        # pre-encode role tokens once
        self._sys_tok = self.tok("system", add_special_tokens=False).input_ids + self.nl_id
        self._usr_tok = self.tok("user",    add_special_tokens=False).input_ids + self.nl_id
        self._asst_tok= self.tok("assistant", add_special_tokens=False).input_ids + self.nl_id

    def _encode_dialog(self, msgs: List[Dict]) -> Optional[Dict]:
        roles_map = {"user": self._usr_tok, "assistant": self._asst_tok, "system": self._sys_tok}
        # system header
        system_message = "You are a testing developer assistant. You must help to automatize the process of generating test oracles, token-by-token."
        if msgs and msgs[0].get("role") == "system" and msgs[0].get("content"):
            system_message = msgs[0]["content"]

        ids = []
        lbl = []

        sys_ids = [self.im_start_id] + roles_map["system"] + \
                  self.tok(system_message, add_special_tokens=False).input_ids + \
                  [self.im_end_id] + self.nl_id
        ids += sys_ids
        lbl += [self.im_start_id] + [IGNORE_INDEX]*(len(sys_ids)-3) + [self.im_end_id] + self.nl_id

        turns = msgs[1:] if msgs and msgs[0].get("role") == "system" else msgs
        for j, s in enumerate(turns):
            role = s["role"]
            if role not in roles_map:
                raise ValueError(f"Unknown role: {role}")

            content_ids = self.tok(s["content"], add_special_tokens=False).input_ids
            seg = self.tok.convert_tokens_to_ids("<|im_start|>")  # not used; we have im_start_id
            _ids = roles_map[role]
            full = [self.im_start_id] + _ids + content_ids + [self.im_end_id] + self.nl_id
            ids += full

            if role == "user" or (self.only_last and j < len(turns) - 1):
                tgt = [self.im_start_id] + [IGNORE_INDEX]*(len(full)-3) + [self.im_end_id] + self.nl_id
            elif role == "assistant":
                # mask role tokens only; supervise the assistant content
                prefix_len = 1 + len(_ids)  # <|im_start|> + role + \n
                tgt = [self.im_start_id] + [IGNORE_INDEX]*prefix_len + full[prefix_len+1:-2] + [self.im_end_id] + self.nl_id
            else:  # system in the middle
                tgt = [self.im_start_id] + [IGNORE_INDEX]*(len(full)-3) + [self.im_end_id] + self.nl_id
            lbl += tgt
        # length control
        if len(ids) > self.maxlen:
            if self.drop_long:
                return None
            # truncate from the right (labels must match)
            ids  = ids[:self.maxlen]
            lbl  = lbl[:self.maxlen]
        return {
            "input_ids": torch.tensor(ids, dtype=torch.long),
            "labels": torch.tensor(lbl, dtype=torch.long),
        }

    def __iter__(self) -> Iterator[Dict[str, torch.Tensor]]:
        # figure out global sharding factor
        if dist.is_available() and dist.is_initialized():
            world_size = dist.get_world_size()
            rank = dist.get_rank()
        else:
            world_size = 1
            rank = 0

        winfo = get_worker_info()
        if winfo is None:
            num_workers = 1
            worker_id = 0
        else:
            num_workers = winfo.num_workers
            worker_id = winfo.id

        shards = world_size * num_workers
        my_shard = rank * num_workers + worker_id
        counter = 0

        for path in self.files:
            with io.open(path, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    # shard by line index across all files
                    if (counter % shards) != my_shard:
                        counter += 1
                        continue
                    counter += 1
                    try:
                        obj = json.loads(line)
                    except Exception:
                        continue
                    msgs = obj["messages"] if "messages" in obj else obj
                    out = self._encode_dialog(msgs)
                    if out is not None:
                        yield out


def smart_tokenizer_and_embedding_resize(
    special_tokens_dict: Dict,
    tokenizer: transformers.PreTrainedTokenizer,
    model: transformers.PreTrainedModel
):
    """Resize tokenizer and embedding.

    Note: This is the unoptimized version that may make your embedding size not be divisible by 64.
    """
    num_new_tokens = tokenizer.add_special_tokens(special_tokens_dict)
    model.resize_token_embeddings(len(tokenizer))

    if num_new_tokens > 0:
        input_embeddings = model.get_input_embeddings().weight.data
        output_embeddings = model.get_output_embeddings().weight.data

        input_embeddings_avg = input_embeddings[:-num_new_tokens].mean(dim=0, keepdim=True)
        output_embeddings_avg = output_embeddings[:-num_new_tokens].mean(dim=0, keepdim=True)

        input_embeddings[-num_new_tokens:] = input_embeddings_avg
        output_embeddings[-num_new_tokens:] = output_embeddings_avg


def _tokenize_fn(strings: Sequence[str], tokenizer: transformers.PreTrainedTokenizer) -> Dict:
    """Tokenize a list of strings."""
    tokenized_list = [
        tokenizer(
            text,
            return_tensors="pt",
            padding="longest",
            max_length=tokenizer.model_max_length,
            truncation=True,
        )
        for text in strings
    ]
    input_ids = labels = [tokenized.input_ids[0] for tokenized in tokenized_list]
    input_ids_lens = labels_lens = [
        tokenized.input_ids.ne(tokenizer.pad_token_id).sum().item() for tokenized in tokenized_list
    ]
    return dict(
        input_ids=input_ids,
        labels=labels,
        input_ids_lens=input_ids_lens,
        labels_lens=labels_lens,
    )

def make_supervised_data_module(tokenizer: transformers.PreTrainedTokenizer, args) -> Dict:
    """Make dataset and collator for supervised fine-tuning."""
    def get_jsonl_paths_for_split(data_dir: str, split_csv: str) -> List[str]:
        project_ids = set()
        with open(split_csv) as f:
            for row in csv.DictReader(f):
                project_ids.add(row["project_id"])
        paths = []
        for f in os.listdir(data_dir):
            if not f.endswith(".jsonl"):
                continue
            pid = f.replace("oracles-datapoints-", "").split("-")[0]
            if pid in project_ids:
                paths.append(os.path.join(data_dir, f))
        return sorted(paths)

    train_files = get_jsonl_paths_for_split(args.data_dir, args.train_file)
    eval_files = get_jsonl_paths_for_split(args.data_dir, args.eval_file)
    train_dataset = JsonlIterable(train_files, tokenizer, args.model_max_length, only_last_turn_loss=True, drop_long=False)
    eval_dataset  = JsonlIterable(eval_files,  tokenizer, args.model_max_length, only_last_turn_loss=True, drop_long=False)
    data_collator = DataCollatorForSupervisedDataset(tokenizer=tokenizer, pad_to_multiple_of=8)
    return dict(train_dataset=train_dataset, eval_dataset=eval_dataset, data_collator=data_collator)

def is_master(args):
    return is_main_process(args.local_rank)


class SaveModelCallback(transformers.TrainerCallback):
    def on_save(self, args, state, control, **kwargs):
        if is_master(args):
            print(f"[save] {args.output_dir}/checkpoint-{state.global_step}/")
        return control


class LoggingCallback(transformers.TrainerCallback):
    def __init__(self, log_memory: bool = True):
        self.start_time = None
        self.last_time = None
        self.last_step = 0
        self.total_tokens_seen = 0
        self.log_memory = log_memory

    def on_log(self, args, state, control, logs=None, **kwargs):
        if logs is None:
            return
        now = time.time()
        world = getattr(args, "world_size", 1)

        if self.start_time is None:
            self.start_time = now
            self.last_time = now

        # Prefer Trainer's running token counter if available (>= 4.41)
        include_tok_seen = getattr(args, "include_num_input_tokens_seen", False)
        if include_tok_seen and hasattr(state, "num_input_tokens_seen") and state.num_input_tokens_seen is not None:
            delta_tokens = (state.num_input_tokens_seen - self.total_tokens_seen) * world
            self.total_tokens_seen = state.num_input_tokens_seen
        else:
            # fallback: estimate
            steps_elapsed = state.global_step - self.last_step
            est_tokens_per_step = args.per_device_train_batch_size * args.model_max_length * world
            delta_tokens = est_tokens_per_step * max(0, steps_elapsed)
            self.last_step = state.global_step

        dt = max(1e-6, now - self.last_time)
        tps = int(delta_tokens / dt)

        log_message = {
            "step": state.global_step,
            "epoch": state.epoch,
            "loss": logs.get("loss"),
            "eval_loss": logs.get("eval_loss"),
            "lr": logs.get("learning_rate"),
            "grad_norm": logs.get("grad_norm"),
            "tokens_per_sec": tps,
        }

        if self.log_memory and torch.cuda.is_available():
            try:
                mem = torch.cuda.max_memory_allocated() // 1_000_000
                log_message["gpu_mem_MiB"] = int(mem)
                torch.cuda.reset_peak_memory_stats()
            except Exception:
                pass

        if is_main_process(args.local_rank):
            print("\n------------------")
            print(log_message)
            print("------------------")

        self.last_time = now


def find_latest_checkpoint(output_dir):
    """Find the latest checkpoint in the output directory."""
    if not os.path.exists(output_dir):
        return None
    checkpoints = [d for d in os.listdir(output_dir) if d.startswith("checkpoint-")]
    if not checkpoints:
        return None
    latest_checkpoint = max(checkpoints, key=lambda x: int(x.split("-")[-1]))
    return os.path.join(output_dir, latest_checkpoint)


class CustomTrainer(Trainer):
    def log(self, logs: Dict[str, float]) -> None:
        if self.state.epoch is not None:
            logs["epoch"] = self.state.epoch
        if self.args.include_num_input_tokens_seen:
            logs["num_input_tokens_seen"] = self.state.num_input_tokens_seen

        # Safely move logs off GPU
        output = {
            k: (
                v.item() if isinstance(v, torch.Tensor) and v.numel() == 1
                else v.detach().cpu().tolist() if isinstance(v, torch.Tensor)
                else v
            )
            for k, v in {**logs, "step": self.state.global_step}.items()
        }

        # Optional: truncate history if needed
        # if len(self.state.log_history) > 1000:
        #     self.state.log_history.pop(0)

        self.state.log_history.append(output)

        # Must keep this
        self.control = self.callback_handler.on_log(self.args, self.state, self.control, logs)


def train():
    parser = transformers.HfArgumentParser((ModelArguments, DataArguments, TrainingArguments))
    model_args, data_args, training_args = parser.parse_args_into_dataclasses()
    args = {**model_args.__dict__, **data_args.__dict__, **training_args.__dict__}
    args = argparse.Namespace(**args)

    # Configure quantization

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,  # A100: prefer BF16 for stability
    )

    attn_impl = "flash_attention_2" if model_args.use_flash_attention else "sdpa"

    model = transformers.AutoModelForCausalLM.from_pretrained(
        model_args.model_name_or_path,
        quantization_config=bnb_config,
        attn_implementation=attn_impl,  # <- enable Flash-2 or SDPA
        trust_remote_code=True,
    )

    # QLoRA prep (does layernorm casting, gradients on inputs, etc.)
    model = prepare_model_for_kbit_training(
        model,
        use_gradient_checkpointing=True
    )
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.config.use_cache = False  # MUST be False during training (saves memory)

    if training_args.use_peft:
        peft_config = LoraConfig(
            r=16,  # r=8→16 modest memory bump, usually better quality
            lora_alpha=32,  # scale with r
            lora_dropout=0.05,
            bias="none",
            task_type="CAUSAL_LM",
            target_modules=None,  # let PEFT pick defaults for Qwen; or list attention/MLP proj
        )
        model = get_peft_model(model, peft_config)
        model.print_trainable_parameters()

    tokenizer = transformers.AutoTokenizer.from_pretrained(
        model_args.model_name_or_path,
        cache_dir=None,
        model_max_length=training_args.model_max_length,
        truncation=True,
        padding_side="right",
        trust_remote_code=True,
    )

    if tokenizer.pad_token_id is None:
        tokenizer.pad_token = tokenizer.eos_token

    num_new = tokenizer.add_special_tokens({"additional_special_tokens": ["<|im_start|>", "<|im_end|>"]})
    if num_new > 0:
        model.resize_token_embeddings(len(tokenizer), pad_to_multiple_of=8)

    data_module = make_supervised_data_module(tokenizer=tokenizer, args=args)

    trainer = CustomTrainer(
        model=model,
        tokenizer=tokenizer,
        args=training_args,
        **data_module,
        callbacks=[
            LoggingCallback(log_memory=True),   # <-- instantiate
            SaveModelCallback(),                # <-- instantiate
            transformers.EarlyStoppingCallback(early_stopping_patience=2),
        ]
    )
    trainer.train()
    trainer.save_state()
    trainer.save_model(output_dir=training_args.output_dir)
    model.save_pretrained(training_args.output_dir + "_lora")

if __name__ == "__main__":
    train()