#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Complete fine-tuning script with:
1. Tokenizer loading diagnostics
2. Model setup diagnostics
3. Step-by-step sample generation logging
"""

import os
import sys
import json
import glob
import logging
from dataclasses import dataclass, field
from typing import Optional, Dict, List
from pathlib import Path
import torch
from datasets import Dataset
from transformers import (
    AutoTokenizer, AutoModelForCausalLM, TrainingArguments, Trainer,
    EarlyStoppingCallback, BitsAndBytesConfig, TrainerCallback,
)
from peft import LoraConfig, get_peft_model
from peft.utils import prepare_model_for_kbit_training
from huggingface_hub import snapshot_download
import transformers

os.environ.setdefault("WANDB_DISABLED", "true")

logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S", level=logging.INFO,
)
logger = logging.getLogger(__name__)

@dataclass
class ModelArguments:
    model_name_or_path: str = field(metadata={"help": "Model path"})
    model_revision: Optional[str] = field(default=None, metadata={"help": "Model revision"})
    use_flash_attention: bool = field(default=False, metadata={"help": "Flash attn"})
    use_peft: bool = field(default=True, metadata={"help": "Use LoRA"})

@dataclass
class DataArguments:
    tokenized_dir: str = field(metadata={"help": "Tokenized data dir"})
    model_max_length: int = field(default=8192, metadata={"help": "Max length"})
    debug_samples: int = field(default=1, metadata={"help": "Samples per step to print"})

@dataclass
class TrainingArgumentsCustom(TrainingArguments):
    early_stopping_patience: int = field(default=2, metadata={"help": "Early stopping"})

class JSONDataset:
    def __init__(self, data_dir: str, split: str):
        self.data_dir = data_dir
        self.split = split
        self.samples = []
        pattern = os.path.join(data_dir, f"{split}.*.json")
        json_files = sorted(glob.glob(pattern))
        if not json_files:
            raise ValueError(f"No JSON files for {split}")
        for json_file in json_files:
            with open(json_file, 'r') as f:
                data = json.load(f)
                self.samples.extend(data.get("samples", []))

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        sample = self.samples[idx]
        return {
            "input_ids": torch.tensor(sample["input_ids"], dtype=torch.long),
            "labels": torch.tensor(sample["labels"], dtype=torch.long)
        }

    def to_hf_dataset(self):
        data_dict = {
            "input_ids": [s["input_ids"] for s in self.samples],
            "labels": [s["labels"] for s in self.samples],
        }
        return Dataset.from_dict(data_dict)

@dataclass
class DataCollatorForOraclePrediction:
    pad_token_id: int

    def __call__(self, features):
        for f in features:
            if not isinstance(f["input_ids"], torch.Tensor):
                f["input_ids"] = torch.tensor(f["input_ids"], dtype=torch.long)
            if not isinstance(f["labels"], torch.Tensor):
                f["labels"] = torch.tensor(f["labels"], dtype=torch.long)

        max_len = max(len(f["input_ids"]) for f in features)
        batch_size = len(features)

        input_ids = torch.full((batch_size, max_len), self.pad_token_id, dtype=torch.long)
        labels = torch.full((batch_size, max_len), -100, dtype=torch.long)
        attention_mask = torch.zeros((batch_size, max_len), dtype=torch.long)

        for i, f in enumerate(features):
            seq_len = len(f["input_ids"])
            input_ids[i, :seq_len] = f["input_ids"]
            labels[i, :seq_len] = f["labels"]
            attention_mask[i, :seq_len] = 1

        return {
            "input_ids": input_ids,
            "labels": labels,
            "attention_mask": attention_mask
        }

class StepDebugCallback(TrainerCallback):
    """Print sample generations at EACH training step"""
    def __init__(self, tokenizer, num_samples=1):
        self.tokenizer = tokenizer
        self.num_samples = num_samples
        self.first_batch_logged = False
        self.sample_cache = {}
        self.model = None  # cache the model once

    def on_train_begin(self, args, state, control, **kwargs):
        """Cache first batch on training start"""
        self.model = kwargs.get("model")  # store accelerated/wrapped model

        dataloader = kwargs.get("train_dataloader")
        if not dataloader:
            return

        batch_iter = iter(dataloader)
        try:
            batch = next(batch_iter)
            self.sample_cache = {
                "input_ids": batch["input_ids"][:self.num_samples].cpu(),
                "labels": batch["labels"][:self.num_samples].cpu(),
            }

            print("\n" + "="*70, flush=True)
            print("INITIAL SAMPLES (Before training starts)", flush=True)
            print("="*70, flush=True)
            self._print_samples()
            self.first_batch_logged = True

        except StopIteration:
            pass

    def on_log(self, args, state, control, logs=None, **kwargs):
        """Called EVERY time logging happens - this is guaranteed!"""
        if state.global_step > 0 and self.sample_cache:
            print("\n" + "="*70, flush=True)
            if logs and 'loss' in logs:
                try:
                    print(f"STEP {state.global_step} | Loss: {float(logs['loss']):.4f}", flush=True)
                except Exception:
                    print(f"STEP {state.global_step}", flush=True)
            else:
                print(f"STEP {state.global_step}", flush=True)
            print("="*70, flush=True)
            self._print_samples()

    def _print_samples(self):
        """Generate and print samples using the cached model"""
        if not self.sample_cache or self.model is None:
            return

        model = self.model
        model.eval()
        with torch.no_grad():
            input_ids = self.sample_cache["input_ids"]
            label_ids = self.sample_cache["labels"]

            for i in range(len(input_ids)):
                print(f"\n[Sample {i+1}]", flush=True)

                target_ids = label_ids[i][label_ids[i] != -100]
                if len(target_ids) > 0:
                    target = self.tokenizer.decode(target_ids.tolist(), skip_special_tokens=True)
                    print(f"[TARGET ({len(target_ids)} tokens)]:\n{target.rstrip()[:150]}", flush=True)
                else:
                    target = ""

                try:
                    device = next(model.parameters()).device
                    input_ids_device = input_ids[i].unsqueeze(0).to(device)
                    attention_mask_device = (input_ids[i].unsqueeze(0) != self.tokenizer.pad_token_id).to(device)

                    pred_ids = model.generate(
                        input_ids_device,
                        attention_mask=attention_mask_device,
                        max_new_tokens=100,
                        do_sample=False,
                        pad_token_id=self.tokenizer.pad_token_id,
                        eos_token_id=self.tokenizer.eos_token_id,
                    )[0]

                    generated_ids = pred_ids[input_ids[i].shape[0]:]
                    generated = self.tokenizer.decode(generated_ids.tolist(), skip_special_tokens=True)

                    print(f"[GENERATED ({len(generated_ids)} tokens)]:\n{generated.rstrip()[:150]}", flush=True)

                    target_clean = target.strip()
                    generated_clean = generated.strip()
                    if target_clean and generated_clean == target_clean:
                        print("✅ MATCH!", flush=True)
                    else:
                        print("❌ No match", flush=True)

                except Exception as e:
                    print(f"[GENERATION ERROR]: {e}", flush=True)

        model.train()

def main():
    print("\n" + "="*70)
    print("DIAGNOSTIC: Starting fine-tuning setup")
    print("="*70)

    parser = transformers.HfArgumentParser((ModelArguments, DataArguments, TrainingArgumentsCustom))

    print("\n[1] Parsing arguments...")
    if len(sys.argv) == 2 and sys.argv[1].endswith(".json"):
        model_args, data_args, training_args = parser.parse_json_file(json_file=os.path.abspath(sys.argv[1]))
    else:
        model_args, data_args, training_args = parser.parse_args_into_dataclasses()

    print(f"[2] Received parameters:")
    print(f"    - model_name_or_path: {model_args.model_name_or_path}")
    print(f"    - tokenized_dir: {data_args.tokenized_dir}")
    print(f"    - model_max_length: {data_args.model_max_length}")

    log_level = training_args.get_process_log_level()
    logger.setLevel(log_level)
    transformers.utils.logging.set_verbosity(log_level)

    # ===== TOKENIZER LOADING WITH DIAGNOSTICS =====
    print("\n[3] Loading tokenizer...")
    tokenizer_path = os.path.join(data_args.tokenized_dir, "tokenizer")
    print(f"    Looking for saved tokenizer at: {tokenizer_path}")

    if os.path.exists(tokenizer_path):
        print(f"    ✅ FOUND! Loading from saved location...")
        tokenizer = AutoTokenizer.from_pretrained(tokenizer_path, trust_remote_code=True, use_fast=True)
        print(f"    ✅ Tokenizer loaded successfully!")
    else:
        print(f"    ❌ NOT FOUND in {tokenizer_path}")
        print(f"    ⚠️  Loading from model: {model_args.model_name_or_path}")
        tokenizer = AutoTokenizer.from_pretrained(
            model_args.model_name_or_path, trust_remote_code=True, use_fast=True)
        print(f"    Fallback tokenizer loaded")

    if tokenizer.pad_token is None:
        print(f"    Setting pad_token = eos_token")
        tokenizer.pad_token = tokenizer.eos_token

    tokenizer.model_max_length = data_args.model_max_length

    print(f"    📊 Tokenizer stats:")
    print(f"       - Vocab size: {len(tokenizer)}")
    print(f"       - Model max length: {tokenizer.model_max_length}")
    print(f"       - Pad token ID: {tokenizer.pad_token_id}")
    print(f"       - EOS token ID: {tokenizer.eos_token_id}")

    # ===== MODEL LOADING WITH DIAGNOSTICS =====
    print("\n[4] Loading model...")
    print(f"    Model: {model_args.model_name_or_path}")

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True, bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16
    )
    print(f"    Using 4-bit quantization")

    attn_implementation = "flash_attention_2" if model_args.use_flash_attention else "sdpa"
    print(f"    Attention implementation: {attn_implementation}")

    model = AutoModelForCausalLM.from_pretrained(
        model_args.model_name_or_path, revision=model_args.model_revision,
        quantization_config=bnb_config, attn_implementation=attn_implementation,
        trust_remote_code=True,
    )
    print(f"    ✅ Model loaded!")
    print(f"    📊 Model stats:")
    print(f"       - Model vocab size: {model.config.vocab_size}")
    print(f"       - Tokenizer vocab size: {len(tokenizer)}")

    if len(tokenizer) > model.config.vocab_size:
        print(f"    ⚠️  Vocab mismatch! Resizing embeddings...")
        print(f"       - From: {model.config.vocab_size}")
        print(f"       - To: {len(tokenizer)}")
        model.resize_token_embeddings(len(tokenizer))
        print(f"    ✅ Embeddings resized!")

    # ===== PEFT SETUP WITH DIAGNOSTICS =====
    print("\n[5] Setting up LoRA/PEFT...")
    if model_args.use_peft:
        model = prepare_model_for_kbit_training(model)
        peft_config = LoraConfig(
            r=16, lora_alpha=32,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
            lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
        )
        model = get_peft_model(model, peft_config)
        model.print_trainable_parameters()

    # ===== DATASET LOADING WITH DIAGNOSTICS =====
    print("\n[6] Loading datasets...")
    train_dataset = JSONDataset(data_args.tokenized_dir, "train").to_hf_dataset()
    val_dataset = JSONDataset(data_args.tokenized_dir, "val").to_hf_dataset()
    print(f"    ✅ Train samples: {len(train_dataset)}")
    print(f"    ✅ Val samples: {len(val_dataset)}")

    # ===== TRAINER SETUP =====
    print("\n[7] Creating trainer...")
    data_collator = DataCollatorForOraclePrediction(pad_token_id=tokenizer.pad_token_id)

    callbacks = [StepDebugCallback(tokenizer, num_samples=1)]
    if training_args.load_best_model_at_end:
        callbacks.append(EarlyStoppingCallback(early_stopping_patience=training_args.early_stopping_patience))

    trainer = Trainer(
        model=model, args=training_args,
        train_dataset=train_dataset, eval_dataset=val_dataset,
        processing_class=tokenizer, data_collator=data_collator,
        callbacks=callbacks,
    )
    print(f"    ✅ Trainer created!")

    print("\n" + "="*70)
    print("[8] Starting training...")
    print("="*70)

    train_result = trainer.train()

    print(f"\n[9] Saving model...")
    trainer.save_model()

    metrics = train_result.metrics
    trainer.log_metrics("train", metrics)
    trainer.save_metrics("train", metrics)
    trainer.save_state()

    if val_dataset is not None:
        print(f"[10] Running final evaluation...")
        metrics = trainer.evaluate()
        trainer.log_metrics("eval", metrics)
        trainer.save_metrics("eval", metrics)

    print("\n" + "="*70)
    print("✅ Training completed!")
    print("="*70 + "\n")

if __name__ == "__main__":
    main()
