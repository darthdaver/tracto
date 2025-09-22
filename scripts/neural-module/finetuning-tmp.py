"""Script to fine-tune CodeLlama model on a custom dataset.
Sources:
- https://github.com/meta-llama/llama-recipes/blob/main/src/llama_recipes/finetuning.py
- https://github.com/meta-llama/llama-recipes/blob/main/src/llama_recipes/datasets/samsum_dataset.py
- https://github.com/meta-llama/llama-recipes/blob/main/src/llama_recipes/datasets/alpaca_dataset.py
- https://github.com/meta-llama/llama-recipes/blob/main/src/llama_recipes/datasets/grammar_dataset/grammar_dataset.py
- https://github.com/salesforce/CodeT5/blob/main/CodeT5%2B/tune_codet5p_seq2seq.py
- https://github.com/salesforce/CodeT5/blob/main/CodeT5%2B/instruct_tune_codet5p.py
- https://github.com/ragntune/code-llama-finetune/blob/main/fine-tune-code-llama.ipynb
- https://huggingface.co/docs/transformers/main/model_doc/code_llama
- https://huggingface.co/codellama/CodeLlama-7b-hf
- https://huggingface.co/docs/transformers/main/model_doc/llama
- https://arxiv.org/pdf/2308.12950.pdf
"""

import os
import pprint
import argparse
import torch

from transformers import (
    AutoTokenizer,
    AutoModelForCausalLM,
    Trainer,
    TrainingArguments,
    DataCollatorForLanguageModeling,
)
from datasets import load_dataset


def load_tokenize_data(args, tokenizer):
    # Load CSV with columns: src, tgt
    dataset = load_dataset("csv", data_files=args.train_data, split="train", keep_default_na=False)

    def preprocess_single(example):
        # Replace <mask_id> with a natural prompt
        source_text = example["src"].replace("<mask_id>", "\n\nWhat assertion should be used here?")

        # Format in instruction-tuned format used by Qwen
        prompt = (
            f"<|im_start|>user\n{source_text}\n<|im_end|>\n"
            f"<|im_start|>assistant\n{example['tgt']}\n<|im_end|>"
        )

        tokens = tokenizer(prompt, max_length=args.max_source_len, truncation=True, padding=False)
        tokens["labels"] = tokens["input_ids"].copy()
        return tokens

    train_data = dataset.map(
        preprocess_single,
        remove_columns=dataset.column_names,
        num_proc=args.num_proc
    )

    return train_data


def run_training(args, train_data, model, tokenizer):
    training_args = TrainingArguments(
        output_dir=args.output_dir,
        per_device_train_batch_size=args.batch_size,
        num_train_epochs=args.epochs,
        save_strategy="epoch",
        do_train=True,
        do_eval=bool(args.eval_data),
        evaluation_strategy="epoch" if args.eval_data else "no",
        logging_steps=10,
        fp16=True,
        report_to="none",  # Disable wandb/huggingface by default
    )

    data_collator = DataCollatorForLanguageModeling(tokenizer=tokenizer, mlm=False)

    eval_data = None
    if args.eval_data:
        eval_dataset = load_dataset("csv", data_files=args.eval_data, split="train", keep_default_na=False)

        def preprocess_eval(example):
            source_text = example["src"].replace("<mask_id>", "\n\nWhat assertion should be used here?")
            prompt = (
                f"<|im_start|>user\n{source_text}\n<|im_end|>\n"
                f"<|im_start|>assistant\n{example['tgt']}\n<|im_end|>"
            )
            tokens = tokenizer(prompt, max_length=args.max_source_len, truncation=True, padding=False)
            tokens["labels"] = tokens["input_ids"].copy()
            return tokens

        eval_data = eval_dataset.map(
            preprocess_eval,
            remove_columns=eval_dataset.column_names,
            num_proc=args.num_proc,
        )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=train_data,
        eval_dataset=eval_data,
        data_collator=data_collator,
    )

    trainer.train()


def main(args):
    print(pprint.pformat(vars(args)))

    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    tokenizer.pad_token = tokenizer.eos_token  # Required for causal models

    model = AutoModelForCausalLM.from_pretrained(
        args.model_path,
        torch_dtype=torch.float16,
        trust_remote_code=True
    )

    model.resize_token_embeddings(len(tokenizer))

    train_data = load_tokenize_data(args, tokenizer)

    run_training(args, train_data, model, tokenizer)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Fine-tune Qwen model")

    parser.add_argument("--model", default="Qwen/Qwen2.5-Coder-32B-Instruct", type=str, help="Model name or HF repo")
    parser.add_argument("--model_path", default="path/to/local/model", type=str, help="Path to local model weights")
    parser.add_argument("--output_dir", default="output", type=str, help="Output directory")
    parser.add_argument("--train_data", required=True, type=str, help="Path to training CSV")
    parser.add_argument("--eval_data", default=None, type=str, help="Path to evaluation CSV (optional)")
    parser.add_argument("--max-source-len", default=2048, type=int, help="Max total token length")
    parser.add_argument("--batch-size", default=1, type=int, help="Batch size per device")
    parser.add_argument("--epochs", default=3, type=int, help="Number of training epochs")
    parser.add_argument("--num_proc", default=4, type=int, help="Parallel preprocessing")

    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)

    main(args)
