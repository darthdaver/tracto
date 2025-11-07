#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Simplified fine-tuning script for full oracle prediction with Qwen models.
Enhanced with detailed logging for debugging.

Removes constraint decoding logic and trains the model to generate complete oracles.
Uses LoRA/PEFT for memory-efficient fine-tuning.

Example:
python finetune_oracle_simplified.py \
    --model_name_or_path Qwen/Qwen2.5-Coder-7B \
    --tokenized_dir /path/to/tokenized \
    --output_dir /path/to/output \
    --model_max_length 4096 \
    --num_train_epochs 3 \
    --debug_samples 5
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
)
from transformers.trainer_utils import PREFIX_CHECKPOINT_DIR
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
import transformers

# Setup logging
logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(name)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
    level=logging.INFO,
)
logger = logging.getLogger(__name__)


@dataclass
class ModelArguments:
    """Arguments for model configuration."""
    model_name_or_path: str = field(
        metadata={"help": "Path to pretrained model or model identifier from huggingface.co/models"}
    )
    model_revision: Optional[str] = field(
        default=None,
        metadata={"help": "Model revision (commit hash or tag)"}
    )
    use_flash_attention: bool = field(
        default=False,
        metadata={"help": "Whether to use Flash Attention 2"}
    )
    use_peft: bool = field(
        default=True,
        metadata={"help": "Whether to use PEFT/LoRA for efficient fine-tuning"}
    )


@dataclass
class DataArguments:
    """Arguments for data configuration."""
    tokenized_dir: str = field(
        metadata={"help": "Directory containing tokenized data (JSON files)"}
    )
    model_max_length: int = field(
        default=8192,
        metadata={"help": "Maximum sequence length"}
    )
    debug_samples: int = field(
        default=5,
        metadata={"help": "Number of samples to debug log at the start of training"}
    )


@dataclass
class TrainingArgumentsCustom(TrainingArguments):
    """Custom training arguments."""
    min_early_stopping_epochs: int = field(
        default=1,
        metadata={"help": "Minimum epochs before early stopping can trigger"}
    )
    early_stopping_patience: int = field(
        default=2,
        metadata={"help": "Number of evaluations with no improvement after which training will be stopped"}
    )


class JSONDataset:
    """
    Dataset loader for JSON-formatted tokenized data.

    Loads data from JSON files with format:
    {
        "metadata": {...},
        "samples": [
            {"input_ids": [...], "labels": [...], "length": N},
            ...
        ]
    }
    """

    def __init__(self, data_dir: str, split: str):
        """
        Initialize JSON dataset loader.

        Args:
            data_dir: Directory containing JSON files
            split: Split name (train or val)
        """
        self.data_dir = data_dir
        self.split = split
        self.samples = []

        # Load all JSON files for this split
        pattern = os.path.join(data_dir, f"{split}.*.json")
        json_files = sorted(glob.glob(pattern))

        if not json_files:
            raise ValueError(f"No JSON files found for split '{split}' in {data_dir}")

        logger.info(f"Loading {split} data from {len(json_files)} JSON files")

        for json_file in json_files:
            try:
                with open(json_file, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    self.samples.extend(data.get("samples", []))
            except Exception as e:
                logger.error(f"Failed to load {json_file}: {e}")
                raise

        logger.info(f"Loaded {len(self.samples)} samples for {split} split")

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, idx):
        """Get a single sample."""
        sample = self.samples[idx]
        return {
            "input_ids": torch.tensor(sample["input_ids"], dtype=torch.long),
            "labels": torch.tensor(sample["labels"], dtype=torch.long),
        }

    def to_hf_dataset(self) -> Dataset:
        """Convert to HuggingFace Dataset format."""
        data_dict = {
            "input_ids": [s["input_ids"] for s in self.samples],
            "labels": [s["labels"] for s in self.samples],
        }
        return Dataset.from_dict(data_dict)


@dataclass
class DataCollatorForOraclePrediction:
    """
    Data collator for oracle prediction.

    Handles batching of variable-length sequences with proper padding.
    """

    pad_token_id: int

    def __call__(self, features: List[Dict[str, torch.Tensor]]) -> Dict[str, torch.Tensor]:
        """
        Collate features into a batch.

        Args:
            features: List of samples with input_ids and labels

        Returns:
            Batched tensors with proper padding
        """
        # Find max length in batch
        max_len = max(len(f["input_ids"]) for f in features)

        batch_size = len(features)

        # Initialize padded tensors
        input_ids = torch.full((batch_size, max_len), self.pad_token_id, dtype=torch.long)
        labels = torch.full((batch_size, max_len), -100, dtype=torch.long)
        attention_mask = torch.zeros((batch_size, max_len), dtype=torch.long)

        # Fill in actual values
        for i, f in enumerate(features):
            seq_len = len(f["input_ids"])
            input_ids[i, :seq_len] = f["input_ids"]
            labels[i, :seq_len] = f["labels"]
            attention_mask[i, :seq_len] = 1

        return {
            "input_ids": input_ids,
            "labels": labels,
            "attention_mask": attention_mask,
        }


class EarlyStoppingWithMinEpochs(EarlyStoppingCallback):
    """
    Early stopping callback that respects minimum epochs.
    """

    def __init__(self, early_stopping_patience: int, min_epochs: int):
        super().__init__(early_stopping_patience=early_stopping_patience)
        self.min_epochs = min_epochs

    def check_metric_value(self, args, state, control, metric_value):
        """Override to check minimum epochs first."""
        # Don't stop before minimum epochs
        if state.epoch < self.min_epochs:
            return

        # Call parent implementation
        super().check_metric_value(args, state, control, metric_value)


class DebugCallback(transformers.TrainerCallback):
    """
    Callback to log detailed debug information during training.
    Logs input prompts, target oracles, and model predictions.
    """

    def __init__(self, tokenizer: AutoTokenizer, num_debug_samples: int = 5):
        self.tokenizer = tokenizer
        self.num_debug_samples = num_debug_samples
        self.debug_logged = False
        self.step_count = 0

    def on_step_end(self, args, state, control, **kwargs):
        """Log debug info at the beginning and periodically."""
        # Log at start and every 500 steps
        if not self.debug_logged or (state.global_step > 0 and state.global_step % 500 == 0):
            self._log_debug_samples(kwargs.get("model"), kwargs.get("train_dataloader"))
            self.debug_logged = True

    def _log_debug_samples(self, model, dataloader):
        """Log detailed information about a few samples."""
        if model is None or dataloader is None:
            return

        logger.info("=" * 80)
        logger.info("DEBUG: Sample Analysis")
        logger.info("=" * 80)

        model.eval()
        with torch.no_grad():
            # Get a few samples from the dataloader
            batch_iter = iter(dataloader)
            try:
                batch = next(batch_iter)
            except StopIteration:
                logger.warning("Could not get batch for debug logging")
                return

            # Process only first few samples
            num_samples = min(self.num_debug_samples, batch["input_ids"].size(0))

            for idx in range(num_samples):
                input_ids = batch["input_ids"][idx]
                labels = batch["labels"][idx]
                attention_mask = batch["attention_mask"][idx]

                # Move to device
                device = next(model.parameters()).device
                input_ids_device = input_ids.unsqueeze(0).to(device)
                attention_mask_device = attention_mask.unsqueeze(0).to(device)

                # Decode input and target
                input_text = self._decode_sequence(input_ids, labels)

                # Generate prediction
                try:
                    with torch.cuda.amp.autocast(dtype=torch.bfloat16):
                        outputs = model.generate(
                            input_ids_device,
                            attention_mask=attention_mask_device,
                            max_new_tokens=200,
                            do_sample=False,
                            pad_token_id=self.tokenizer.pad_token_id,
                            eos_token_id=self.tokenizer.eos_token_id,
                        )

                    # Decode only the generated part (after input)
                    generated_ids = outputs[0][input_ids_device.size(1):]
                    generated_text = self.tokenizer.decode(generated_ids, skip_special_tokens=True)
                except Exception as e:
                    generated_text = f"[Generation failed: {e}]"

                # Log the analysis
                logger.info(f"{'=' * 80}")
                logger.info(f"SAMPLE {idx + 1}/{num_samples}")
                logger.info(f"{'=' * 80}")
                #logger.info(f"[FULL INPUT (with special tokens)]: {input_text['full_input']}")
                #logger.info(f"[PROMPT ONLY (what model sees)]: {input_text['prompt_only']}")
                logger.info(f" [TARGET ORACLE (ground truth)]: {input_text['target']}")
                logger.info(f"[GENERATED OUTPUT]: {generated_text}")

                # Check if prediction matches target
                target_clean = input_text['target'].strip()
                generated_clean = generated_text.strip()
                match = target_clean == generated_clean
                logger.info(f"[MATCH]: {'✓ CORRECT' if match else '✗ INCORRECT'}")

                if not match:
                    logger.info(f"[DIFFERENCE]:")
                    logger.info(f"  Expected: {repr(target_clean)}")
                    logger.info(f"  Got:      {repr(generated_clean)}")

                logger.info(f"[TOKEN STATS]:")
                logger.info(f"  Total length: {len(input_ids)}")
                logger.info(f"  Prompt tokens: {(labels == -100).sum().item()}")
                logger.info(f"  Target tokens: {(labels != -100).sum().item()}")

        model.train()
        logger.info("=" * 80 + "")

    def _decode_sequence(self, input_ids: torch.Tensor, labels: torch.Tensor) -> Dict[str, str]:
        """
        Decode input_ids and labels to show prompt and target separately.

        Returns dict with:
        - full_input: Complete input with special tokens
        - prompt_only: Just the prompt part (where labels == -100)
        - target: Just the target oracle (where labels != -100)
        """
        # Get valid tokens (not padding)
        valid_mask = input_ids != self.tokenizer.pad_token_id
        valid_input_ids = input_ids[valid_mask]
        valid_labels = labels[valid_mask]

        # Full input with special tokens
        full_input = self.tokenizer.decode(valid_input_ids, skip_special_tokens=False)

        # Prompt only (where labels are masked)
        prompt_mask = valid_labels == -100
        prompt_ids = valid_input_ids[prompt_mask]
        prompt_only = self.tokenizer.decode(prompt_ids, skip_special_tokens=False)

        # Target only (where labels are not masked)
        target_mask = valid_labels != -100
        target_ids = valid_labels[target_mask]
        target = self.tokenizer.decode(target_ids, skip_special_tokens=True)

        return {
            "full_input": full_input,
            "prompt_only": prompt_only,
            "target": target,
        }


def load_tokenizer(model_args: ModelArguments, data_args: DataArguments) -> AutoTokenizer:
    """Load and configure tokenizer."""
    # First, try loading from tokenized_dir
    tokenizer_path = os.path.join(data_args.tokenized_dir, "tokenizer")

    if os.path.exists(tokenizer_path):
        logger.info(f"Loading tokenizer from {tokenizer_path}")
        tokenizer = AutoTokenizer.from_pretrained(
            tokenizer_path,
            trust_remote_code=True,
            use_fast=True,
        )
    else:
        logger.info(f"Loading tokenizer from {model_args.model_name_or_path}")
        tokenizer = AutoTokenizer.from_pretrained(
            model_args.model_name_or_path,
            revision=model_args.model_revision,
            trust_remote_code=True,
            use_fast=True,
        )

    # Set padding token
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    tokenizer.model_max_length = data_args.model_max_length

    return tokenizer


def load_model(model_args: ModelArguments, tokenizer: AutoTokenizer) -> AutoModelForCausalLM:
    """Load and configure model."""
    logger.info(f"Loading model from {model_args.model_name_or_path}")

    # Configure attention implementation
    attn_implementation = "flash_attention_2" if model_args.use_flash_attention else "sdpa"

    # Load model
    model = AutoModelForCausalLM.from_pretrained(
        model_args.model_name_or_path,
        revision=model_args.model_revision,
        trust_remote_code=True,
        torch_dtype=torch.bfloat16,
        attn_implementation=attn_implementation,
    )

    # Resize token embeddings if needed
    if len(tokenizer) > model.get_input_embeddings().weight.shape[0]:
        logger.info(f"Resizing token embeddings from {model.get_input_embeddings().weight.shape[0]} to {len(tokenizer)}")
        model.resize_token_embeddings(len(tokenizer))

    # Apply PEFT if requested
    if model_args.use_peft:
        logger.info("Applying LoRA/PEFT configuration")

        # LoRA configuration optimized for code generation
        peft_config = LoraConfig(
            r=16,  # LoRA rank
            lora_alpha=32,  # LoRA alpha (scaling factor)
            target_modules=[
                "q_proj",
                "k_proj",
                "v_proj",
                "o_proj",
                "gate_proj",
                "up_proj",
                "down_proj",
            ],
            lora_dropout=0.05,
            bias="none",
            task_type="CAUSAL_LM",
        )

        # Prepare model for training
        model = prepare_model_for_kbit_training(model)
        model = get_peft_model(model, peft_config)

        # Print trainable parameters
        model.print_trainable_parameters()

    return model


def load_datasets(data_args: DataArguments):
    """Load train and validation datasets."""
    logger.info("Loading datasets")

    # Load train dataset
    train_dataset = JSONDataset(data_args.tokenized_dir, "train")
    train_ds = train_dataset.to_hf_dataset()

    # Load validation dataset
    val_dataset = JSONDataset(data_args.tokenized_dir, "val")
    val_ds = val_dataset.to_hf_dataset()

    logger.info(f"Train samples: {len(train_ds)}")
    logger.info(f"Val samples: {len(val_ds)}")

    return train_ds, val_ds


def main():
    """Main training function."""
    # Parse arguments
    parser = transformers.HfArgumentParser((ModelArguments, DataArguments, TrainingArgumentsCustom))

    if len(sys.argv) == 2 and sys.argv[1].endswith(".json"):
        # Load from JSON file
        model_args, data_args, training_args = parser.parse_json_file(json_file=os.path.abspath(sys.argv[1]))
    else:
        model_args, data_args, training_args = parser.parse_args_into_dataclasses()

    # Setup logging
    logging.basicConfig(
        format="%(asctime)s - %(levelname)s - %(name)s - %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=[logging.StreamHandler(sys.stdout)],
    )

    log_level = training_args.get_process_log_level()
    logger.setLevel(log_level)
    transformers.utils.logging.set_verbosity(log_level)
    transformers.utils.logging.enable_default_handler()
    transformers.utils.logging.enable_explicit_format()

    logger.info(f"Training/evaluation parameters {training_args}")

    # Load tokenizer
    tokenizer = load_tokenizer(model_args, data_args)
    logger.info(f"Tokenizer vocab size: {len(tokenizer)}")

    # Load model
    model = load_model(model_args, tokenizer)

    # Load datasets
    train_dataset, eval_dataset = load_datasets(data_args)

    # Create data collator
    data_collator = DataCollatorForOraclePrediction(pad_token_id=tokenizer.pad_token_id)

    # Setup callbacks
    callbacks = []

    # Add debug callback
    if data_args.debug_samples > 0:
        debug_callback = DebugCallback(tokenizer, num_debug_samples=data_args.debug_samples)
        callbacks.append(debug_callback)
        logger.info(f"Debug callback enabled: will log {data_args.debug_samples} samples")

    # Add early stopping
    if training_args.load_best_model_at_end:
        callbacks.append(
            EarlyStoppingWithMinEpochs(
                early_stopping_patience=training_args.early_stopping_patience,
                min_epochs=training_args.min_early_stopping_epochs,
            )
        )

    # Initialize trainer
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_dataset,
        eval_dataset=eval_dataset,
        tokenizer=tokenizer,
        data_collator=data_collator,
        callbacks=callbacks,
    )

    # Train
    logger.info("Starting training")
    train_result = trainer.train()

    # Save model
    logger.info(f"Saving model to {training_args.output_dir}")
    trainer.save_model()

    # Save training metrics
    metrics = train_result.metrics
    trainer.log_metrics("train", metrics)
    trainer.save_metrics("train", metrics)
    trainer.save_state()

    # Final evaluation
    if eval_dataset is not None:
        logger.info("Running final evaluation")
        metrics = trainer.evaluate()
        trainer.log_metrics("eval", metrics)
        trainer.save_metrics("eval", metrics)

    logger.info("Training completed!")


if __name__ == "__main__":
    main()
