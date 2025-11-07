#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Simplified fine-tuning script for full oracle prediction with Qwen models.
- 4-bit quantization (BitsAndBytes)
- LoRA/PEFT adapters
- Per-sample in-training debug logging: prompt, target, generated output for the first N steps
- Standard Hugging Face early stopping callback
"""

import os
import sys
import json
import glob
import logging
from dataclasses import dataclass, field
from typing import Optional, Dict, List
import torch
from datasets import Dataset
from transformers import (
    AutoTokenizer,
    AutoModelForCausalLM,
    TrainingArguments,
    Trainer,
    EarlyStoppingCallback,
    BitsAndBytesConfig,
)
from transformers.trainer_utils import PREFIX_CHECKPOINT_DIR
from peft import LoraConfig, get_peft_model
from peft.utils import prepare_model_for_kbit_training
import transformers

os.environ.setdefault("WANDB_DISABLED", "true")

logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)

@dataclass
class ModelArguments:
    model_name_or_path: str = field(metadata={"help": "Path to pretrained model or model identifier"})
    model_revision: Optional[str] = field(default=None, metadata={"help": "Model revision"})
    use_flash_attention: bool = field(default=False, metadata={"help": "Use Flash Attention"})
    use_peft: bool = field(default=True, metadata={"help": "Use LoRA/PEFT"})

@dataclass
class DataArguments:
    tokenized_dir: str = field(metadata={"help": "Directory with tokenized JSON files"})
    model_max_length: int = field(default=8192, metadata={"help": "Max sequence length"})
    debug_samples: int = field(default=3, metadata={"help": "How many samples to log for training debug"})

@dataclass
class TrainingArgumentsCustom(TrainingArguments):
    early_stopping_patience: int = field(default=2, metadata={"help": "Patience for early stopping"})

class JSONDataset:
    def __init__(self, data_dir: str, split: str):
        self.data_dir = data_dir
        self.split = split
        self.samples = []
        pattern = os.path.join(data_dir, f"{split}.*.json")
        json_files = sorted(glob.glob(pattern))
        if not json_files:
            raise ValueError(f"No JSON files found for split '{split}' in {data_dir}")
        for json_file in json_files:
            with open(json_file, 'r', encoding='utf-8') as f:
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
    def to_hf_dataset(self) -> Dataset:
        data_dict = {
            "input_ids": [s["input_ids"] for s in self.samples],
            "labels": [s["labels"] for s in self.samples],
        }
        return Dataset.from_dict(data_dict)

@dataclass
class DataCollatorForOraclePrediction:
    pad_token_id: int
    def __call__(self, features: List[Dict[str, torch.Tensor]]) -> Dict[str, torch.Tensor]:
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

class DebugTrainer(Trainer):
    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        """Override compute_loss to accept num_items_in_batch parameter and add debug logging."""
        outputs = model(**inputs)
        loss = outputs.loss
        input_ids = inputs["input_ids"].detach().cpu()
        label_ids = inputs["labels"].detach().cpu()
        batch = min(len(input_ids), self.args.per_device_train_batch_size or 2)
        for i in range(batch):
            prompt = self.processing_class.decode(input_ids[i].tolist(), skip_special_tokens=False)
            target = self.processing_class.decode(label_ids[i][label_ids[i] != -100].tolist(), skip_special_tokens=True)
            with torch.no_grad():
                pred_ids = model.generate(
                    input_ids[i].unsqueeze(0).to(model.device),
                    attention_mask=(input_ids[i].unsqueeze(0) != self.processing_class.pad_token_id).to(model.device),
                    max_new_tokens=128,
                    do_sample=False,
                    pad_token_id=self.processing_class.pad_token_id,
                    eos_token_id=self.processing_class.eos_token_id,
                )[0]
                generated = self.processing_class.decode(pred_ids[input_ids[i].shape[0]:], skip_special_tokens=True)
            print("\n" + "="*50)
            print(f"Step {self.state.global_step} Sample {i}")
            #print("[PROMPT]\n" + prompt.rstrip())
            print("[TARGET]\n" + target.rstrip())
            print("[GENERATED]\n" + generated.rstrip())
            print("="*50)

        return (loss, outputs) if return_outputs else loss

def load_tokenizer(model_args: ModelArguments, data_args: DataArguments) -> AutoTokenizer:
    tokenizer_path = os.path.join(data_args.tokenized_dir, "tokenizer")
    if os.path.exists(tokenizer_path):
        logger.info(f"Loading tokenizer from {tokenizer_path}")
        tokenizer = AutoTokenizer.from_pretrained(tokenizer_path, trust_remote_code=True, use_fast=True)
    else:
        logger.info(f"Loading tokenizer from {model_args.model_name_or_path}")
        tokenizer = AutoTokenizer.from_pretrained(
            model_args.model_name_or_path, revision=model_args.model_revision, trust_remote_code=True, use_fast=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    tokenizer.model_max_length = data_args.model_max_length
    return tokenizer

def load_model(model_args: ModelArguments, tokenizer: AutoTokenizer) -> AutoModelForCausalLM:
    logger.info(f"Loading model from {model_args.model_name_or_path}")
    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16
    )
    attn_implementation = "flash_attention_2" if model_args.use_flash_attention else "sdpa"
    model = AutoModelForCausalLM.from_pretrained(
        model_args.model_name_or_path,
        revision=model_args.model_revision,
        quantization_config=bnb_config,
        attn_implementation=attn_implementation,
        trust_remote_code=True,
    )
    if len(tokenizer) > model.config.vocab_size:
        logger.info(f"Resizing token embeddings from {model.config.vocab_size} to {len(tokenizer)}")
        model.resize_token_embeddings(len(tokenizer))
    if model_args.use_peft:
        logger.info("Applying LoRA/PEFT configuration")
        model = prepare_model_for_kbit_training(model)
        peft_config = LoraConfig(
            r=16, lora_alpha=32, target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
            lora_dropout=0.05, bias="none", task_type="CAUSAL_LM",
        )
        model = get_peft_model(model, peft_config)
        model.print_trainable_parameters()
    return model

def load_datasets(data_args: DataArguments):
    logger.info("Loading datasets")
    train_dataset = JSONDataset(data_args.tokenized_dir, "train").to_hf_dataset()
    val_dataset = JSONDataset(data_args.tokenized_dir, "val").to_hf_dataset()
    logger.info(f"Train samples: {len(train_dataset)}")
    logger.info(f"Val samples: {len(val_dataset)}")
    return train_dataset, val_dataset

def main():
    parser = transformers.HfArgumentParser((ModelArguments, DataArguments, TrainingArgumentsCustom))
    if len(sys.argv) == 2 and sys.argv[1].endswith(".json"):
        model_args, data_args, training_args = parser.parse_json_file(json_file=os.path.abspath(sys.argv[1]))
    else:
        model_args, data_args, training_args = parser.parse_args_into_dataclasses()

    log_level = training_args.get_process_log_level()
    logger.setLevel(log_level)
    transformers.utils.logging.set_verbosity(log_level)
    transformers.utils.logging.enable_default_handler()
    transformers.utils.logging.enable_explicit_format()
    logger.info(f"Training/evaluation parameters {training_args}")

    tokenizer = load_tokenizer(model_args, data_args)
    logger.info(f"Tokenizer vocab size: {len(tokenizer)}")

    model = load_model(model_args, tokenizer)

    train_dataset, eval_dataset = load_datasets(data_args)

    data_collator = DataCollatorForOraclePrediction(pad_token_id=tokenizer.pad_token_id)

    callbacks = []
    if data_args.debug_samples > 0:
        logger.info(f"Debug logging enabled: will log {data_args.debug_samples} samples per batch step")
    if training_args.load_best_model_at_end:
        callbacks.append(EarlyStoppingCallback(early_stopping_patience=training_args.early_stopping_patience))

    trainer = DebugTrainer(
        model=model,
        args=training_args,
        train_dataset=train_dataset,
        eval_dataset=eval_dataset,
        processing_class=tokenizer,  # Use processing_class instead of tokenizer
        data_collator=data_collator,
        callbacks=callbacks,
    )

    logger.info("Starting training")
    train_result = trainer.train()

    logger.info(f"Saving model to {training_args.output_dir}")
    trainer.save_model()

    metrics = train_result.metrics
    trainer.log_metrics("train", metrics)
    trainer.save_metrics("train", metrics)
    trainer.save_state()

    if eval_dataset is not None:
        logger.info("Running final evaluation")
        metrics = trainer.evaluate()
        trainer.log_metrics("eval", metrics)
        trainer.save_metrics("eval", metrics)

    logger.info("Training completed!")

if __name__ == "__main__":
    main()
