import os
import sys
import tqdm
import pandas as pd
from typing import Dict

sys.path.append(os.path.join(os.path.dirname(__file__), "..", ".."))
from src.parsers.DatasetInferenceArgs import DatasetInferenceArgs
from src.parsers.ModelArgs import ModelArgs
from src.utils.Logger import Logger
import torch
from datasets import load_dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    DataCollatorForLanguageModeling,
    HfArgumentParser
)

# Setup the logger
logger = Logger("main", "infer")


def preprocess_dp(dp: Dict[str, str], src_col, max_len: int, tokenizer: AutoTokenizer, bos_token: list):
    """
    Preprocess a single datapoint from the dataset.
    Args:
        dp (`Dict[str, str]`):
            The datapoint to preprocess. It contains the source and target columns.
        src_col (`str`):
            The name of the source column in the dataset.
        max_len (`int`):
            The maximum length of the input sequence.
        tokenizer (`AutoTokenizer`):
            The tokenizer to use to preprocess the datapoint.
        bos_token (`list`):
            The beginning-of-sequence token to manually add to the input. It contains a single token.
    """
    input_ids = bos_token + tokenizer(dp[src_col], max_length=max_len, truncation=True)["input_ids"]
    attention_mask = [1] * len(input_ids)
    return {
        "input_ids": input_ids,
        "attention_mask": attention_mask,
    }

def save_prediction_stats(filepath: str, inputs: list, targets: list, predictions: list):
    """
    Save the prediction stats to a CSV file.
    Args:
        filepath (`str`):
            The path to the output CSV file.
        inputs (`list`):
            The list of input sequences.
        targets (`list`):
            The list of target sequences.
        predictions (`list`):
            The list of predicted sequences.
    """
    df = pd.DataFrame({'input': inputs, \
                       'target': targets, \
                       'prediction': predictions, \
                       'match': [True if p == t else False for p, t in zip(predictions, targets)]})
    df.to_csv(filepath, index=False)

def check_model_accuracy(targets, predictions):
    """
    Check the accuracy of the model.
    Args:
        targets (`list`):
            The list of target sequences.
        predictions (`list`):
            The list of predicted sequences.
    Returns:
        The accuracy of the model (in percentage).
    """
    #assert len(targets) == len(predictions), f"Targets size: {len(targets)} != Predictions size: {len(predictions)}"
    # compare two sets
    perfect_predictions = 0
    for x,y in zip(targets, predictions):
        if str(x) == str(y):
            perfect_predictions += 1
    accuracy = perfect_predictions*100.0/len(targets)
    return float(accuracy)

if __name__ == "__main__":
    # Read the args passed to the script
    parser = HfArgumentParser((ModelArgs, DatasetInferenceArgs))
    model_args, data_args = parser.parse_args_into_dataclasses()
    # Set output path
    output_path = data_args.output_path
    # Log the arguments
    logger.log("=" * 50)
    logger.log(f"Dataset path: {data_args.path}")
    logger.log(f"Checkpoints path: {model_args.model_name_or_path}")
    logger.log(f"Predictions path: {output_path}")
    logger.log("=" * 50)
    # Load the model and tokenizer
    logger.log(f"Loading model {model_args.model_name_or_path} and tokenizer {model_args.tokenizer_name}...")
    tokenizer = AutoTokenizer.from_pretrained(model_args.tokenizer_name)
    # Prevents the tokenizer from automatically adding beginning-of-sequence (BOS) and end-of-sequence (EOS) tokens
    tokenizer.add_bos_token = False
    tokenizer.add_eos_token = False
    # Define special token for padding (it will require to resize the token embeddings after loading the model)
    tokenizer.add_special_tokens({"pad_token": "<pad>"})
    # Get BOS token to manually add it to the input, when needed
    bos_token = [tokenizer.bos_token_id]
    # Load the model and resize the token embeddings
    model = AutoModelForCausalLM.from_pretrained(
        model_args.model_name_or_path,
        torch_dtype=torch.float16,
        device_map="auto"
    )
    model.resize_token_embeddings(len(tokenizer))
    # Load the dataset
    logger.log(f"Loading dataset {data_args.path}")
    dataset = load_dataset('csv', data_files={data_args.split: data_args.path}, keep_default_na=False)
    # Get the column names from the dataset
    col_names = dataset[data_args.dataset_split].column_names
    src_col = data_args.src_col
    tgt_col = data_args.tgt_col
    if src_col not in col_names:
        raise ValueError(f"--source_column' value '{data_args.src_col}' needs to be one of: {', '.join(col_names)}")
    if tgt_col not in col_names:
        raise ValueError(f"--target_column' value '{data_args.tgt_col}' needs to be one of: {', '.join(col_names)}")
    # Preprocess the dataset
    logger.log("Preprocessing the dataset...")
    p_dataset = dataset[data_args.split].map(lambda dp: preprocess_dp(dp, src_col, data_args.max_src_len, tokenizer, bos_token))
    logger.log(f"Loaded {len(p_dataset)} samples for prediction")
    logger.log(f"Example:\n\nInput:\n{p_dataset[0][src_col]}\n\nTarget:\n{p_dataset[0][tgt_col]}")
    # Set the format of the dataset (torch tensors for input_ids and attention_mask)
    p_dataset.set_format(type='torch', columns=['input_ids', 'attention_mask'])
    dataloader = torch.utils.data.DataLoader(
        p_dataset,
        batch_size=data_args.batch_size,
        collate_fn=DataCollatorForLanguageModeling(tokenizer, pad_to_multiple_of=8, mlm=False, padding=True)
    )
    # Define the generation kwargs
    gen_kwargs = {
        "max_new_tokens": data_args.max_target_length,
        "num_beams": data_args.num_beams,
        "do_sample": False,
        "pad_token_id": tokenizer.eos_token_id
    }
    # Set the model to evaluation mode
    model.eval()
    # Generate predictions
    logger.log("Generating predictions...")
    predictions = []
    for i, batch in enumerate(tqdm(dataloader)):
        batch.to(model.device)
        batch = {k: v for k, v in batch.items()}
        with torch.no_grad():
            out = model.generate(**batch, **gen_kwargs)
            decoded_out = tokenizer.batch_decode(out[:, batch['input_ids'].shape[1]:], skip_special_tokens=True)
            predictions.extend(out)
    # Save the predictions
    logger.log(f"Saving predictions to {output_path}")
    with open(output_path, 'w', encoding='utf-8', errors='replace') as f:
        for pred in predictions:
            f.write(f"{pred}\n")
    assert len(predictions) == len(p_dataset)
    # Export prediction stats
    df = dataset[data_args.dataset_split].to_pandas()
    inputs = df[data_args.src_col].tolist()
    targets = df[data_args.tgt_col].tolist()
    save_prediction_stats(output_path.replace('.txt', '.csv'), inputs, targets, predictions)
    # Print accuracy
    accuracy = check_model_accuracy(targets, predictions)
    print(f'Model accuracy: {str(accuracy)}')