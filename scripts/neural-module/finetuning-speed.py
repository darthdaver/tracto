import os, time, json, argparse, logging
from dataclasses import dataclass, field
from typing import Dict, Optional, List, Tuple
from torch.utils.data import Sampler
from itertools import chain
import math, numpy as np, random
import os, multiprocessing as mp
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
os.environ.setdefault("HF_DATASETS_DISABLE_PARALLELISM", "1")
try:
    mp.set_start_method("spawn", force=True)
except RuntimeError:
    pass
import numpy as np
import torch, torch.distributed as dist
torch.backends.cuda.matmul.allow_tf32 = True
torch.backends.cudnn.allow_tf32 = True
from torch.utils.data import Dataset, get_worker_info
import transformers
from transformers import Trainer
from transformers.trainer_utils import is_main_process
from transformers import BitsAndBytesConfig
from torch.utils.data import DataLoader

from peft.utils import prepare_model_for_kbit_training
from peft import get_peft_model, LoraConfig

IGNORE_INDEX = -100
logging.basicConfig(level=logging.INFO)

# -------------------------
# Args
# -------------------------

@dataclass
class ModelArguments:
    model_name_or_path: Optional[str] = field(default="Qwen/Qwen2.5-Coder-14B-Instruct")
    use_flash_attention: bool = field(default=False, metadata={"help": "Whether to use Flash Attention."})

@dataclass
class DataArguments:
    # Root directory containing train.* and val.* shards + meta JSONs (output of the builder)
    tokenized_dir: str = field(default=None, metadata={"help": "Folder with pre-tokenized shards: train.*.{bin,idx}, val.*.{bin,idx}, and meta JSONs."})

@dataclass
class TrainingArguments(transformers.TrainingArguments):
    output_dir: Optional[str] = field(default=os.path.join(os.path.dirname(__file__), "output", "ft"))
    cache_dir: Optional[str] = field(default=None)
    optim: str = field(default="adamw_torch")
    model_max_length: int = field(default=8192)
    num_train_epochs: int = field(default=3)
    use_peft: bool = field(default=True)
    deepspeed: Optional[str] = None
    dataloader_num_workers: int = 8
    dataloader_persistent_workers: bool = True
    dataloader_pin_memory: bool = True
    gradient_checkpointing: bool = True
    bf16: bool = True
    fp16: bool = False
    per_device_train_batch_size: int = 1
    per_device_eval_batch_size: int = 1
    gradient_accumulation_steps: int = 16
    group_by_length: bool = False   # not used with plain PyTorch Dataset
    ddp_find_unused_parameters: bool = False
    eval_accumulation_steps: int = 1
    prediction_loss_only: bool = True
    max_grad_norm: float = 0.3
    report_to: Optional[str] = "none"
    torch_compile: bool = False
    remove_unused_columns: bool = False
    include_num_input_tokens_seen: bool = False

    # Early stopping guard: don't allow stop before at least N full epochs
    min_early_stopping_epochs: int = 1
    early_stopping_patience: int = 2  # pass via CLI in bash

class BlockDistributedSampler(Sampler):
    """
    Deterministic block sampler for DDP:
      • Same shuffle on every rank per epoch (uses seed+epoch)
      • Drops partial blocks so all blocks have identical size
      • Trims to a multiple of world_size, then partitions by striding
      • Drops tail to a multiple of batch_size so all ranks have identical steps
    """
    def __init__(
        self,
        dataset,
        batch_size: int,
        world_size: int = 1,
        rank: int = 0,
        block_size: int = 512,
        sort_within_block: bool = True,
        seed: int = 42,
    ):
        self.ds = dataset
        self.bs = int(batch_size)
        self.ws = int(world_size)
        self.rank = int(rank)
        self.block_size = int(block_size)
        self.sort_within_block = bool(sort_within_block)
        self.seed = int(seed)
        self.epoch = 0

        # Build full-size blocks of global indices across shards
        self.blocks = []
        start = 0
        for shard in dataset.shards:
            n = int(shard["n"])
            gidx = np.arange(start, start + n, dtype=np.int64)
            full_n = (n // self.block_size) * self.block_size  # drop tail
            if full_n > 0:
                for i in range(0, full_n, self.block_size):
                    blk = gidx[i : i + self.block_size]
                    if self.sort_within_block:
                        lens = dataset.lengths[blk]
                        blk = blk[np.argsort(-lens, kind="mergesort")]  # longest→shortest
                    self.blocks.append(blk)
            start += n

        if len(self.blocks) == 0:
            raise RuntimeError(
                "BlockDistributedSampler: no full blocks were constructed. "
                "Decrease block_size or check dataset."
            )

        # Pre-compute per-rank length (stable across epochs)
        usable_blocks = (len(self.blocks) // self.ws) * self.ws
        per_rank_blocks = usable_blocks // self.ws
        per_rank_samples = per_rank_blocks * self.block_size
        # After drop_last at batch granularity
        self._len = (per_rank_samples // self.bs) * self.bs

    def set_epoch(self, epoch: int):
        # HF/Accelerate calls this each epoch; used to vary the shuffle deterministically.
        self.epoch = int(epoch)

    def __iter__(self):
        # Same permutation on every rank for this epoch
        rng = random.Random(self.seed + self.epoch)
        blocks = list(self.blocks)
        rng.shuffle(blocks)

        # Trim to multiple of world_size; then deterministic partition by striding
        usable = (len(blocks) // self.ws) * self.ws
        blocks = blocks[:usable]
        rank_blocks = blocks[self.rank :: self.ws]

        # Flatten indices for this rank
        idx = np.concatenate(rank_blocks, axis=0)

        # Ensure equal number of batches on every rank
        if self.bs > 1:
            idx = idx[: (len(idx) // self.bs) * self.bs]

        return iter(idx.tolist())

    def __len__(self) -> int:
        return self._len

# -------------------------
# Tokenizer helpers
# -------------------------

def load_tokenizer_consistent(model_name: str, model_max_length: int, tokenized_dir: str):
    """
    Load the tokenizer in the exact same way as the builder did.
    If a saved tokenizer exists under tokenized_dir/tokenizer/, prefer that.
    Else load from model hub and add the same special tokens.
    """
    saved_tok_dir = os.path.join(tokenized_dir, "tokenizer")
    if os.path.isdir(saved_tok_dir):
        tok = transformers.AutoTokenizer.from_pretrained(saved_tok_dir, trust_remote_code=True)
    else:
        tok = transformers.AutoTokenizer.from_pretrained(
            model_name, trust_remote_code=True, padding_side="right"
        )
        if tok.pad_token_id is None:
            tok.pad_token = tok.eos_token
        # Suppress "longer than max length" warnings; we enforce our own cap in data
        tok.model_max_length = 1_000_000_000
        tok.add_special_tokens({"additional_special_tokens": ["<|im_start|>", "<|im_end|>"]})
    # Set runtime cap for attention mask creation/padding
    tok.model_max_length = model_max_length
    return tok

def assert_meta_compatible(tokenized_dir: str, tok: transformers.PreTrainedTokenizer, split: str):
    """
    Basic sanity checks between meta and the runtime tokenizer.
    """
    meta_path = os.path.join(tokenized_dir, f"{split}.meta.json")
    if not os.path.isfile(meta_path):
        logging.warning(f"{meta_path} not found; skipping tokenizer/meta consistency checks for split={split}.")
        return
    with open(meta_path, "r") as f:
        meta = json.load(f)
    # pad/eos should match; special tokens should exist
    if "vocab_size" in meta:
        assert len(tok) == meta["vocab_size"], f"vocab size mismatch: {len(tok)} vs {meta['vocab_size']}"
    sti = meta.get("special_token_ids")
    if sti:
        assert tok("<|im_start|>").input_ids[0] == sti["<|im_start|>"]
        assert tok("<|im_end|>").input_ids[0] == sti["<|im_end|>"]
    if "pad_token_id" in meta and meta["pad_token_id"] is not None:
        assert tok.pad_token_id == meta["pad_token_id"], \
            f"pad_token_id mismatch: tokenizer={tok.pad_token_id}, meta={meta['pad_token_id']}"
    if "eos_token_id" in meta and meta["eos_token_id"] is not None:
        assert tok.eos_token_id == meta["eos_token_id"], \
            f"eos_token_id mismatch: tokenizer={tok.eos_token_id}, meta={meta['eos_token_id']}"
    for t in ["<|im_start|>", "<|im_end|>"]:
        tid = tok(t).input_ids[0]
        if tid < 0:
            raise RuntimeError(f"Token {t} missing in tokenizer.")

# -------------------------
# Data: memmap shards
# -------------------------

class MemmapShardDataset(Dataset):
    """
    Map-style dataset over pre-tokenized variable-length sequences, with random/shuffled
    sampling handled by Trainer's (Distributed)Sampler. This ensures each epoch traverses
    the entire dataset exactly once per process.

    Directory layout (tokenized_dir):
      train.00000.input_ids.bin
      train.00000.labels.bin
      train.00000.idx
      train.00001.input_ids.bin
      ...
      train.meta.json
      val.00000.input_ids.bin
      ...
      val.meta.json
      [optional] tokenizer/  (saved tokenizer from builder)
    """
    def __init__(self, root: str, split: str):
        self.root = root
        self.split = split

        # Discover shard bases
        import glob
        idx_paths = sorted(glob.glob(os.path.join(root, f"{split}.*.idx")))
        if not idx_paths:
            raise FileNotFoundError(f"No {split} shards (.idx) found in {root}")
        self.shards: List[Dict] = []
        self.cum_counts: List[int] = []
        lengths_chunks: List[np.ndarray] = []
        total = 0
        for idx_path in idx_paths:
            base = os.path.splitext(idx_path)[0]
            ids_path = base + ".input_ids.bin"
            lbl_path = base + ".labels.bin"
            # Load offsets (N+1 entries)
            offsets = np.fromfile(idx_path, dtype=np.uint64)
            if offsets.size < 2:
                continue
            n_samples = offsets.size - 1
            self.shards.append({
                "base": base,
                "ids_path": ids_path,
                "lbl_path": lbl_path,
                "idx_path": idx_path,
                "offsets": offsets,   # small; keep in RAM
                "n": n_samples,
            })
            # ---- length computation (tokens per sample) ----
            lengths = np.diff(offsets).astype(np.int32)  # safe since max_length <= 8192
            lengths_chunks.append(lengths)
            total += n_samples
            self.cum_counts.append(total)

        if total == 0:
            raise RuntimeError(f"{split}: zero samples found in {root}")

        # Concatenate all per-shard lengths into one array aligned with global indices
        self.lengths: np.ndarray = np.concatenate(lengths_chunks, axis=0)  # shape = [__len__()]
        # Per-worker cache for memmaps (opened lazily)
        self._ids_mm = [None] * len(self.shards)
        self._lbl_mm = [None] * len(self.shards)

    def __len__(self) -> int:
        return self.cum_counts[-1]

    def _open_mm(self, shard_idx: int):
        if self._ids_mm[shard_idx] is None:
            ids_path = self.shards[shard_idx]["ids_path"]
            lbl_path = self.shards[shard_idx]["lbl_path"]
            self._ids_mm[shard_idx] = np.memmap(ids_path, mode="r", dtype=np.int32)
            self._lbl_mm[shard_idx] = np.memmap(lbl_path, mode="r", dtype=np.int32)

    def _locate(self, i: int) -> Tuple[int, int]:
        # map global index -> (shard_idx, local_idx)
        import bisect
        shard_idx = bisect.bisect_right(self.cum_counts, i)
        prev_total = 0 if shard_idx == 0 else self.cum_counts[shard_idx-1]
        local_idx = i - prev_total
        return shard_idx, local_idx

    def __getitem__(self, i: int) -> Dict[str, torch.Tensor]:
        shard_idx, j = self._locate(i)
        self._open_mm(shard_idx)
        off = self.shards[shard_idx]["offsets"]
        a = int(off[j])
        b = int(off[j + 1])

        # Copy + cast to torch.long (int64)
        ids = torch.tensor(self._ids_mm[shard_idx][a:b], dtype=torch.long)
        lbl = torch.tensor(self._lbl_mm[shard_idx][a:b], dtype=torch.long)
        return {"input_ids": ids, "labels": lbl}


# -------------------------
# Collator (dynamic pad)
# -------------------------

@dataclass
class DataCollatorForSupervisedDataset:
    tokenizer: transformers.PreTrainedTokenizer
    pad_to_multiple_of: int = 8
    static_pad_to: Optional[int] = None  # keep None for dynamic lengths

    def __call__(self, instances: List[Dict]) -> Dict[str, torch.Tensor]:
        input_ids = [ex["input_ids"] for ex in instances]
        labels    = [ex["labels"]    for ex in instances]

        ids = torch.nn.utils.rnn.pad_sequence(
            input_ids, batch_first=True, padding_value=self.tokenizer.pad_token_id
        )
        lbl = torch.nn.utils.rnn.pad_sequence(
            labels, batch_first=True, padding_value=IGNORE_INDEX
        )

        if self.static_pad_to is not None:
            target_len = int(self.static_pad_to)
        else:
            m = int(self.pad_to_multiple_of) if self.pad_to_multiple_of else 1
            target_len = ((ids.size(1) + m - 1) // m) * m

        if ids.size(1) != target_len:
            pad = target_len - ids.size(1)
            if pad < 0:
                ids = ids[:, :target_len]
                lbl = lbl[:, :target_len]
            else:
                ids = torch.nn.functional.pad(ids, (0, pad), value=self.tokenizer.pad_token_id)
                lbl = torch.nn.functional.pad(lbl, (0, pad), value=IGNORE_INDEX)

        return {
            "input_ids": ids,
            "labels": lbl,
            "attention_mask": ids.ne(self.tokenizer.pad_token_id),
        }

# -------------------------
# Logging + delayed early stop
# -------------------------

def is_master(local_rank):
    return is_main_process(local_rank)

class LocalityTrainer(Trainer):
    @staticmethod
    def _num_items_from_batch(batch):
        # ... (your existing robust implementation unchanged) ...
        if isinstance(batch, dict):
            for k in ("labels", "input_ids", "pixel_values", "input_values"):
                v = batch.get(k)
                if isinstance(v, torch.Tensor):
                    return int(v.shape[0])
                if isinstance(v, (list, tuple)) and v and isinstance(v[0], torch.Tensor):
                    return int(v[0].shape[0])
            for v in batch.values():
                if isinstance(v, torch.Tensor):
                    return int(v.shape[0])
                if isinstance(v, (list, tuple)) and v and isinstance(v[0], torch.Tensor):
                    return int(v[0].shape[0])
        elif isinstance(batch, (list, tuple)) and batch and isinstance(batch[0], torch.Tensor):
            return int(batch[0].shape[0])
        return 1

    def get_num_items(self, batch):
        # Prefer HF's implementation if present; otherwise use our fallback.
        if hasattr(super(), "get_num_items"):
            try:
                return super().get_num_items(batch)
            except Exception:
                pass
        return self._num_items_from_batch(batch)

    def get_batch_samples(self, epoch_iterator, num_batches, device: torch.device | None = None):
        """
        HF-compatible: returns (batch_samples: List[batch_dict], num_items_in_batch: int)
        Works with both signatures: with or without the 'device' arg.
        """
        batch_samples = []
        for _ in range(num_batches):
            try:
                batch_samples.append(next(epoch_iterator))
            except StopIteration:
                break

        # Prefer token count if labels exist; else fall back to item count
        num_items = None
        if batch_samples and isinstance(batch_samples[0], dict) and "labels" in batch_samples[0]:
            try:
                num_items = sum((b["labels"].ne(IGNORE_INDEX)).sum() for b in batch_samples)
            except Exception:
                num_items = None

        if num_items is None:
            per_mb = [self.get_num_items(b) for b in batch_samples]
            num_items = torch.tensor(sum(per_mb))

        # Ensure CUDA + dense before any gather
        if device is None:
            device = getattr(self, "accelerator", None).device if hasattr(self, "accelerator") else torch.device("cuda" if torch.cuda.is_available() else "cpu")

        if not torch.is_tensor(num_items):
            num_items = torch.tensor(num_items, device=device)
        else:
            num_items = num_items.to(device=device)
        if getattr(num_items, "is_sparse", False):
            num_items = num_items.to_dense()

        if getattr(self.args, "average_tokens_across_devices", False):
            num_items = self.accelerator.gather(num_items).sum().item()
        else:
            num_items = int(num_items.item())

        return batch_samples, num_items

    # def get_batch_samples(self, epoch_iterator, num_batches, device):
    #     batch = next(epoch_iterator)
    #     # Get batch size robustly
    #     if hasattr(super(), "get_num_items"):
    #         try:
    #             num_items = super().get_num_items(batch)  # HF provides this
    #         except Exception:
    #             num_items = None
    #     else:
    #         num_items = None
    #     if num_items is None:
    #         # fallback: look for any tensor and take its first dim
    #         if isinstance(batch, dict):
    #             for v in batch.values():
    #                 if isinstance(v, torch.Tensor):
    #                     num_items = int(v.shape[0]);
    #                     break
    #         if num_items is None: num_items = 1
    #
    #     num_items = torch.tensor(num_items, device=device)  # <-- CUDA
    #     return batch, num_items

    def get_train_dataloader(self):
        ds = self.train_dataset
        bsz = self.args.per_device_train_batch_size
        ws = torch.distributed.get_world_size() if torch.distributed.is_initialized() else 1
        rk = torch.distributed.get_rank() if torch.distributed.is_initialized() else 0

        sampler = BlockDistributedSampler(
            dataset=ds,
            batch_size=bsz,
            world_size=ws,
            rank=rk,
            block_size=self.args.per_device_train_batch_size,  # adjust 256–2048 based on storage
            sort_within_block=True,
            seed=42,
        )
        return DataLoader(
            ds,
            batch_size=bsz,
            sampler=sampler,
            collate_fn=self.data_collator,
            num_workers=self.args.dataloader_num_workers,
            pin_memory=self.args.dataloader_pin_memory,
            persistent_workers=self.args.dataloader_persistent_workers,
            prefetch_factor=8,
            drop_last=self.args.dataloader_drop_last,
        )

class SaveModelCallback(transformers.TrainerCallback):
    def on_save(self, args, state, control, **kwargs):
        if is_master(args.local_rank):
            print(f"[save] {args.output_dir}/checkpoint-{state.global_step}/")
        # Make all ranks wait until save on rank0 is done.
        if dist.is_available() and dist.is_initialized():
            dist.barrier()
        return control

class LoggingCallback(transformers.TrainerCallback):
    def __init__(self, log_memory: bool = True):
        self.last_time = None
        self.last_step = 0
        self.total_tokens_seen = 0
        self.log_memory = log_memory

    def on_log(self, args, state, control, logs=None, **kwargs):
        if logs is None: return
        now = time.time()
        world = getattr(args, "world_size", 1)
        if self.last_time is None: self.last_time = now

        include_tok_seen = getattr(args, "include_num_input_tokens_seen", False)
        if include_tok_seen and getattr(state, "num_input_tokens_seen", None) is not None:
            delta_tokens = (state.num_input_tokens_seen - self.total_tokens_seen) * world
            self.total_tokens_seen = state.num_input_tokens_seen
        else:
            steps_elapsed = state.global_step - self.last_step
            est_tokens_per_step = args.per_device_train_batch_size * args.model_max_length * world
            delta_tokens = est_tokens_per_step * max(0, steps_elapsed)
            self.last_step = state.global_step

        dt = max(1e-6, now - self.last_time)
        tps = int(delta_tokens / dt)

        msg = {"step": state.global_step, "epoch": state.epoch,
               "loss": logs.get("loss"), "eval_loss": logs.get("eval_loss"),
               "lr": logs.get("learning_rate"), "tokens_per_sec": tps}
        if self.log_memory and torch.cuda.is_available():
            try:
                mem = torch.cuda.max_memory_allocated() // 1_000_000
                msg["gpu_mem_MiB"] = int(mem)
                torch.cuda.reset_peak_memory_stats()
            except Exception:
                pass
        if is_master(args.local_rank):
            print("\n------------------")
            print(msg)
            print("------------------")
        self.last_time = now

class DelayedEarlyStopping(transformers.EarlyStoppingCallback):
    """
    Wraps Hugging Face EarlyStopping but ignores evaluate calls until epoch >= min_early_stopping_epochs.
    """
    def __init__(self, min_early_stopping_epochs: int = 1, early_stopping_patience: int = 2,
                 early_stopping_threshold: float = 0.0):
        super().__init__(early_stopping_patience=early_stopping_patience,
                         early_stopping_threshold=early_stopping_threshold)
        self._min_epochs = min_early_stopping_epochs

    def on_evaluate(self, args, state, control, **kwargs):
        # Defer early stopping decisions until after min epochs are finished
        if state.epoch is not None and state.epoch < self._min_epochs:
            return control
        return super().on_evaluate(args, state, control, **kwargs)

# -------------------------
# Train
# -------------------------

def make_datasets(tokenized_dir: str):
    train_ds = MemmapShardDataset(tokenized_dir, "train")
    eval_ds  = MemmapShardDataset(tokenized_dir, "val")
    return train_ds, eval_ds

def train():
    parser = transformers.HfArgumentParser((ModelArguments, DataArguments, TrainingArguments))
    model_args, data_args, training_args = parser.parse_args_into_dataclasses()
    args = {**model_args.__dict__, **data_args.__dict__, **training_args.__dict__}
    args = argparse.Namespace(**args)

    assert data_args.tokenized_dir, "--tokenized_dir is required and must point to the pre-tokenized shards."

    # Load tokenizer (matching the builder)
    tokenizer = load_tokenizer_consistent(model_args.model_name_or_path,
                                          training_args.model_max_length,
                                          data_args.tokenized_dir)

    # Model (QLoRA 4-bit)
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True, bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16
    )
    attn_impl = "flash_attention_2" if model_args.use_flash_attention else "sdpa"
    model = transformers.AutoModelForCausalLM.from_pretrained(
        model_args.model_name_or_path,
        quantization_config=bnb_config,
        attn_implementation=attn_impl,
        trust_remote_code=True,
    )

    # Ensure model embeddings match tokenizer (in case additional specials were added)
    model.resize_token_embeddings(len(tokenizer), pad_to_multiple_of=8)
    model.config.pad_token_id = tokenizer.pad_token_id
    model.config.eos_token_id = tokenizer.eos_token_id
    model.config.bos_token_id = tokenizer.bos_token_id
    model = prepare_model_for_kbit_training(model, use_gradient_checkpointing=True)
    if hasattr(model, "generation_config"):
        model.generation_config.pad_token_id = tokenizer.pad_token_id
        model.generation_config.eos_token_id = tokenizer.eos_token_id
        model.config.bos_token_id = tokenizer.bos_token_id
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.config.use_cache = False

    if training_args.use_peft:
        peft_config = LoraConfig(
            r=16, lora_alpha=32, lora_dropout=0.05,
            bias="none", task_type="CAUSAL_LM",
            target_modules=None,
        )
        model = get_peft_model(model, peft_config)
        model.print_trainable_parameters()

    # Sanity-check tokenizer vs meta
    try:
        assert_meta_compatible(data_args.tokenized_dir, tokenizer, "train")
        assert_meta_compatible(data_args.tokenized_dir, tokenizer, "val")
    except AssertionError as e:
        raise RuntimeError(f"Tokenizer/meta mismatch: {e}")

    # Datasets
    train_ds, eval_ds = make_datasets(data_args.tokenized_dir)

    # Collator (dynamic padding to multiple of 8)
    data_collator = DataCollatorForSupervisedDataset(tokenizer=tokenizer, pad_to_multiple_of=8, static_pad_to=None)

    # Trainer
    callbacks = [
        LoggingCallback(log_memory=True),
        SaveModelCallback(),
        DelayedEarlyStopping(min_early_stopping_epochs=training_args.min_early_stopping_epochs,
                             early_stopping_patience=training_args.early_stopping_patience),
    ]

    trainer = LocalityTrainer(
        model=model,
        args=training_args,
        processing_class=tokenizer,
        train_dataset=train_ds,
        eval_dataset=eval_ds,
        data_collator=data_collator,
        callbacks=callbacks,
    )

    trainer.train()
    trainer.save_state()
    trainer.save_model(output_dir=training_args.output_dir)
    model.save_pretrained(training_args.output_dir + "_lora")

if __name__ == "__main__":
    train()
