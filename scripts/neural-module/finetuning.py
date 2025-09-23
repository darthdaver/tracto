import csv
import time
import logging
from dataclasses import dataclass, field
from typing import Dict, Optional, Sequence, List
import argparse
import torch
import transformers
from transformers import Trainer, EarlyStoppingCallback
import torch.distributed as dist
import os
from utils import training_datasets
from peft import get_peft_model, PeftConfig
from transformers.trainer_utils import is_main_process
from peft import PeftConfig
from transformers import BitsAndBytesConfig
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

    def get_dataset_file_paths(self, dataset_type) -> List[str]:
        project_ids = []
        with open(os.path.join(self.data_dir, f"{dataset_type}_projects.csv")) as f:
            reader = csv.DictReader(f)
            for row in reader:
                project_ids.append(row["project_id"])
        return sorted([os.path.join(self.data_dir, f) for f in os.listdir(self.data_dir) if f.endswith('.jsonl') or f.endswith('.npy') and  f.replace("oracles-datapoints-", "").split("-")[0] in project_ids])




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
    per_device_train_batch_size: int = 4
    per_device_eval_batch_size: int = 4
    gradient_accumulation_steps: int = 8
    fp16: bool = True
    deepspeed: Optional[str] = None #"/home/coder/project/tracto/scripts/neural-module/ds_config.json"
    ddp_find_unused_parameters: bool = False
    evaluation_strategy: str = field(default="epoch")
    eval_steps: int = field(default=1000)
    save_strategy: str = field(default="epoch")
    save_steps: int = field(default=1000)
    save_total_limit: int = field(default=1)
    load_best_model_at_end: bool = field(default=True)
    metric_for_best_model: str = field(default="eval_loss")
    greater_is_better: bool = field(default=False)
    logging_strategy: str = field(default="steps")
    logging_steps: int = field(default=10000)


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



@dataclass
class DataCollatorForSupervisedDataset(object):
    """Collate examples for supervised fine-tuning."""
    tokenizer: transformers.PreTrainedTokenizer

    def __call__(self, instances: Sequence[Dict]) -> Dict[str, torch.Tensor]:
        input_ids, labels = tuple([instance[key] for instance in instances] for key in ("input_ids", "labels"))
        input_ids = torch.nn.utils.rnn.pad_sequence(
            input_ids, batch_first=True, padding_value=self.tokenizer.pad_token_id
        )
        labels = torch.nn.utils.rnn.pad_sequence(labels, batch_first=True, padding_value=IGNORE_INDEX)
        return dict(
            input_ids=input_ids,
            labels=labels,
            attention_mask=input_ids.ne(self.tokenizer.pad_token_id),
        )

def make_supervised_data_module(tokenizer: transformers.PreTrainedTokenizer, args) -> Dict:
    """Make dataset and collator for supervised fine-tuning."""

    def load_dataset(file_paths: List[str]):
        datasets = []
        for path in file_paths:
            if path.endswith(".npy") or path.endswith(".jsonl"):
                datasets.append(training_datasets.SupervisedDataset(tokenizer=tokenizer, data_path=path, args=args))
            elif path.endswith(".mmap"):
                datasets.append(training_datasets.MMAPSupervisedDataset(tokenizer=tokenizer, data_path=path, args=args))
        return torch.utils.data.ConcatDataset(datasets) if len(datasets) > 1 else datasets[0]

    train_dataset = load_dataset(args.get_dataset_file_paths("train"))
    eval_dataset = load_dataset(args.get_dataset_file_paths("val"))
    data_collator = DataCollatorForSupervisedDataset(tokenizer=tokenizer)
    return dict(train_dataset=train_dataset, eval_dataset=eval_dataset, data_collator=data_collator)



# def is_master():
#     return dist.get_rank() == 0

def is_master():
    return is_main_process()


class SaveModelCallback(transformers.TrainerCallback):
    def on_save(self, args, state, control, **kwargs):
        output_dir = args.output_dir
        step = state.global_step
        if is_master():
            print(f"Model saved at: {output_dir}/checkpoint-{step}/")
        return control

class LoggingCallback(transformers.TrainerCallback):
    def __init__(self):
        self.start_time = None
        self.last_time = None
        self.last_step = 0
        self.total_tokens = 0

    def on_log(self, args, state, control, logs=None, **kwargs):
        if logs is not None:
            current_time = time.time()
            world_size = args.world_size
            current_step = state.global_step
            if self.start_time is None:
                self.start_time = current_time
                self.last_time = current_time
            if args.include_num_input_tokens_seen:
                tokens_processed = (state.num_input_tokens_seen - self.total_tokens) * world_size
                self.total_tokens = state.num_input_tokens_seen
            else:
                batch_size = args.per_device_train_batch_size
                max_seq_length = args.model_max_length
                steps_elapsed = current_step - self.last_step
                tokens_processed = batch_size * max_seq_length * steps_elapsed * world_size
                self.last_step = current_step
            time_elapsed = current_time - self.last_time
            tokens_per_second = tokens_processed / time_elapsed if time_elapsed > 0 else 0
            self.last_time = current_time
            log_message = {
                "loss": logs.get("loss", None),
                "learning_rate": logs.get("learning_rate", None),
                "epoch": logs.get("epoch", None),
                "step": current_step,
                "grad_norm": logs.get("grad_norm", None),
                "world_size": world_size,
                "tokens_per_second": int(tokens_per_second)
            }
            if is_master():
                print(log_message)


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
        """
        Log `logs` on the various objects watching training.

        Subclass and override this method to inject custom behavior.

        Args:
            logs (`Dict[str, float]`):
                The values to log.
        """
        if self.state.epoch is not None:
            logs["epoch"] = self.state.epoch
        if self.args.include_num_input_tokens_seen:
            logs["num_input_tokens_seen"] = self.state.num_input_tokens_seen

        output = {**logs, **{"step": self.state.global_step}}
        self.state.log_history.append(output)
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
        bnb_4bit_compute_dtype=torch.float16,  # or bfloat16 if your GPU supports it
    )

    # Load model in 4-bit with device_map
    model = transformers.AutoModelForCausalLM.from_pretrained(
        model_args.model_name_or_path,
        quantization_config=bnb_config,
        #device_map="auto",  # allow model to split across 2 GPUs
        trust_remote_code=True
    )

    if training_args.use_peft:
        # Define LoRA config inline
        peft_config = LoraConfig(
            r=8,
            lora_alpha=16,
            lora_dropout=0.1,
            bias="none",
            task_type="CAUSAL_LM"
        )
        model.enable_input_require_grads()
        model = get_peft_model(model, peft_config)
        model.print_trainable_parameters()


    #logging.info(args)
    # model = transformers.AutoModelForCausalLM.from_pretrained(
    #     model_args.model_name_or_path,
    #     cache_dir=training_args.cache_dir,
    #     attn_implementation="flash_attention_2" if model_args.use_flash_attention else None,
    #     trust_remote_code = True
    # )
    # if training_args.use_peft:
    #     peft_config = PeftConfig.from_pretrained(training_args.peft_config_path)
    #     model.enable_input_require_grads()
    #     model = get_peft_model(model, peft_config)
    #     model.print_trainable_parameters()
    tokenizer = transformers.AutoTokenizer.from_pretrained(
        model_args.model_name_or_path,
        pad_token = '<|endoftext|>',
        eos_token = '<|im_end|>', #<|endoftext|>
        cache_dir = None,
        model_max_length = training_args.model_max_length,
        truncation = True,
        padding_side = "right",
        trust_remote_code = True
    )

    tokenizer.add_special_tokens({"additional_special_tokens": ["<|im_end|>", "<|im_start|>"]})
    data_module = make_supervised_data_module(tokenizer=tokenizer, args=args)
    #trainer = CustomTrainer(
    trainer = Trainer(
        model=model,
        tokenizer=tokenizer,
        args=training_args,
        **data_module,
        callbacks=[LoggingCallback, SaveModelCallback, EarlyStoppingCallback(early_stopping_patience=2)],
        evaluation_strategy="epoch",
        eval_steps = 1000,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",  # or another metric like "eval_accuracy"
        greater_is_better=False,  # because lower loss is better
    )
    trainer.train()
    trainer.save_state()
    trainer.save_model(output_dir=training_args.output_dir)
    model.save_pretrained(training_args.output_dir + "_lora")

if __name__ == "__main__":
    train()