#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Balanced, pre-tokenized SFT corpus using existing size bins (Small/Medium/Large).

- Uses train/val CSVs with columns: project_id,num_oracles,size_bin,unmask_tokens
- Allocates token budgets per bin and per project (√-scaling)
- Encodes chat with Qwen tokenizer (+ <|im_start|>, <|im_end|>) OR plain src/tgt pairs (no chat template)
- Assistant-only supervision (IGNORE_INDEX=-100)
- Length buckets by *token length after encoding*: short/med/long

Example:
python build_tokenized_dataset_bins.py \
--model_name_or_path Qwen/Qwen2.5-Coder-14B-Instruct \
--data_dir /path/jsonl \
--train_csv /path/splits/train_projects.csv \
--val_csv /path/splits/val_projects.csv \
--out_dir /path/tokenized \
--train_token_budget 2000000000 \
--val_token_budget 40000000 \
--max_length 4096 \
--bin_allocation sqrt \
--alpha 0.5 \
--target_mix 0.4,0.4,0.2 \
--train_drop_long
"""

import io, csv, glob, math, random, argparse, logging
from sys import prefix
from typing import Dict, List, Tuple, Optional, Iterator, Union, Any
import pandas as pd
import orjson
from transformers import AutoTokenizer, AddedToken
from huggingface_hub import snapshot_download
from pathlib import Path

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# --------------------------
# Global Variables and types
# --------------------------

IGNORE_INDEX = -100
Sample = Union[List[Dict[str, Any]], Tuple[str, str], Tuple[str, str, List[str]]]

tgt_JSON_structure = {
    "prefix": "{\"choice\": \"",
    "suffix": "\"}"
}

# JSON structure tokens - these should always be available for JSON output
JSON_STRUCTURE_TOKENS = {
    "prefix": "{\"choice\":\"",
    "suffix": "\"}"
}

# Generic literal tokens
LITERALS = [
    "DECIMAL_LITERAL", "HEX_LITERAL", "OCT_LITERAL", "BINARY_LITERAL",
    "FLOAT_LITERAL", "HEX_FLOAT_LITERAL", "BOOL_LITERAL", "CHAR_LITERAL",
    "STRING_LITERAL", "LAMDA_PARAM"
]


import os
import json
import numpy as np
import logging

logger = logging.getLogger(__name__)


class JSONWriter:
    """
    JSON writer for input_ids, labels, and constraint sequences.
    Saves data in JSON format with proper structure for easy loading.
    """

    def __init__(self, out_dir: str, split: str, samples_per_file: int = 1000):
        """
        Initialize JSON writer.

        Args:
            out_dir: Output directory
            split: Split name (train/val)
            samples_per_file: Number of samples per JSON file (for manageable file sizes)
        """
        os.makedirs(out_dir, exist_ok=True)
        self.out_dir = out_dir
        self.split = split
        self.samples_per_file = samples_per_file
        self.file_id = 0
        self.current_samples = []
        self.total_samples = 0
        self.total_tokens = 0

    def write(self, input_ids: np.ndarray, labels: np.ndarray, constraint_seqs: List[List[int]]) -> None:
        """
        Write a sample to the current JSON batch.

        Args:
            input_ids: np.ndarray of int32, shape (L,)
            labels: np.ndarray of int32, shape (L,)
            constraint_seqs: List[List[int]], ragged array of constraint token sequences
        """
        try:
            # Convert numpy arrays to lists for JSON serialization
            sample_data = {
                "input_ids": input_ids.tolist() if isinstance(input_ids, np.ndarray) else input_ids,
                "labels": labels.tolist() if isinstance(labels, np.ndarray) else labels,
                "constraints": constraint_seqs if constraint_seqs else [],
                "length": len(input_ids)
            }
            self.current_samples.append(sample_data)
            self.total_samples += 1
            self.total_tokens += len(input_ids)

            # Write to file when batch is full
            if len(self.current_samples) >= self.samples_per_file:
                self._write_batch()
        except Exception as e:
            logger.error(f"Error writing sample to JSON file {self.file_id}: {e}")
            raise

    def _write_batch(self):
        """Write current batch of samples to a JSON file."""
        if not self.current_samples:
            return
        file_path = os.path.join(self.out_dir, f"{self.split}.{self.file_id:05d}.json")
        batch_data = {
            "metadata": {
                "split": self.split,
                "file_id": self.file_id,
                "num_samples": len(self.current_samples),
                "total_tokens": sum(sample["length"] for sample in self.current_samples)
            },
            "samples": self.current_samples
        }
        try:
            with open(file_path, 'w', encoding='utf-8') as f:
                json.dump(batch_data, f, indent=None, separators=(',', ':'))
            logger.info(f"Wrote {len(self.current_samples)} samples to {file_path}")
            # Reset for next batch
            self.current_samples = []
            self.file_id += 1
        except Exception as e:
            logger.error(f"Error writing JSON file {file_path}: {e}")
            raise

    def close(self):
        """Close writer and save any remaining samples."""
        try:
            # Write any remaining samples
            if self.current_samples:
                self._write_batch()
            # Write summary metadata
            summary_path = os.path.join(self.out_dir, f"{self.split}.summary.json")
            summary_data = {
                "split": self.split,
                "total_files": self.file_id,
                "total_samples": self.total_samples,
                "total_tokens": self.total_tokens,
                "samples_per_file": self.samples_per_file
            }
            with open(summary_path, 'w', encoding='utf-8') as f:
                json.dump(summary_data, f, indent=2)
            logger.info(
                f"Closed JSON writer for {self.split}: {self.total_samples} samples, {self.total_tokens} tokens across {self.file_id} files")

        except Exception as e:
            logger.error(f"Error closing JSON writer: {e}")
            raise


# Helper function to read JSON data
def load_json_dataset(json_dir: str, split: str) -> List[Dict[str, Any]]:
    """
    Load dataset from JSON files.

    Args:
        json_dir: Directory containing JSON files
        split: Split name (train/val)

    Returns:
        List of sample dictionaries with keys: input_ids, labels, constraints, length
    """
    import glob
    pattern = os.path.join(json_dir, f"{split}.*.json")
    json_files = sorted(glob.glob(pattern))
    # Exclude summary file
    json_files = [f for f in json_files if not f.endswith('.summary.json')]
    all_samples = []
    for json_file in json_files:
        try:
            with open(json_file, 'r', encoding='utf-8') as f:
                data = json.load(f)
                all_samples.extend(data["samples"])
        except Exception as e:
            logger.error(f"Error loading {json_file}: {e}")
            continue
    return all_samples


class EncoderInterface:
    def encode(self, sample: Sample) -> Tuple[Optional[np.ndarray], Optional[np.ndarray], Optional[List[List[int]]]]:
        """Encode sample and return (input_ids, labels, constraint_tokens)"""
        pass


class PairEncoder(EncoderInterface):
    """
    Encoder for FIM-style plain pairs with constraints support:
    - src = everything up to and INCLUDING <|fim_middle|>
    - tgt = what the model should generate after <|fim_middle|>
    - constraints = list of allowed tokens for constrained generation

    Loss is computed only on tgt (+ optional EOS).
    If the pair is too long, we right-truncate *src* while preserving the
    FIM structure; tgt is never truncated.
    """

    def __init__(
            self,
            tok: AutoTokenizer,
            max_len: int,
            drop_long: bool = False,
            append_eos: bool = True,
            predict_eos: bool = True,
    ):
        # Set tokenizer
        self.tok = tok
        # Set prompt max len
        self.max_len = max_len
        # If set to `True` the tokenizer completely skip the sample if it exceeds
        # the `max_len`. If set to `False` the input is truncated
        self.drop_long = drop_long
        # If set to `True` the tokenizer appends the EOS token at the end of the input sequence
        self.append_eos = append_eos
        self.predict_eos = predict_eos
        self.eos_id = getattr(tok, "eos_token_id", None)

        # Get FIM token IDs with validation
        self.fim_prefix_id = self._get_special_token_id("<|fim_prefix|>")
        self.fim_suffix_id = self._get_special_token_id("<|fim_suffix|>")
        self.fim_middle_id = self._get_special_token_id("<|fim_middle|>")

        if None in [self.fim_prefix_id, self.fim_suffix_id, self.fim_middle_id]:
            raise ValueError("FIM tokens not found in tokenizer vocabulary")

    def _get_special_token_id(self, token: str) -> Optional[int]:
        """Get token ID with proper error handling."""
        try:
            token_ids = self.tok.convert_tokens_to_ids([token])
            if token_ids and token_ids[0] != self.tok.unk_token_id:
                return token_ids[0]
        except Exception as e:
            logger.warning(f"Failed to get token ID for {token}: {e}")
        return None

    def _tokenize_plain(self, text: str) -> List[int]:
        if not text:
            return []
        try:
            # If tgt selection stage
            if text.startswith(tgt_JSON_structure["prefix"]):
                prefix_ids = self.tok.encode(JSON_STRUCTURE_TOKENS["prefix"], add_special_tokens=False)
                suffix_ids = self.tok.encode(JSON_STRUCTURE_TOKENS["suffix"], add_special_tokens=False)
                core_ids = self.tok.encode(text.replace(tgt_JSON_structure["prefix"],"").replace(tgt_JSON_structure["suffix"], ""), add_special_tokens=False)
                return prefix_ids + core_ids + suffix_ids
            return self.tok.encode(text, add_special_tokens=False)
        except Exception as e:
            logger.error(f"Tokenization failed for text length {len(text)}: {e}")
            return []

    def _find(self, ids: List[int], tid: int) -> List[int]:
        return [i for i, t in enumerate(ids) if t == tid]

    def encode(self, sample: Sample) -> Tuple[Optional[np.ndarray], Optional[np.ndarray], Optional[List[List[int]]]]:
        try:
            if len(sample) == 2:
                src, tgt = sample
                constraints = []
            elif len(sample) == 3:
                src, tgt, constraints = sample
            else:
                return None, None, []
            # Validate inputs
            if not src or not tgt:
                logger.debug("Skipping empty src or tgt")
                return None, None, []
            src_ids = self._tokenize_plain(src)
            tgt_ids = self._tokenize_plain(tgt)
            if not src_ids or not tgt_ids:
                logger.debug("Skipping sample with empty tokenization")
                return None, None, []
            # Enhanced FIM validation
            if not src_ids or src_ids[-1] != self.fim_middle_id:
                logger.debug("Skipping src without <|fim_middle|> at end")
                return None, None, []
            # Verify FIM structure more robustly
            pfx_pos = self._find(src_ids, self.fim_prefix_id)
            sfx_pos = self._find(src_ids, self.fim_suffix_id)
            mid_pos = len(src_ids) - 1
            if not pfx_pos or not sfx_pos:
                logger.debug("Missing FIM prefix or suffix tokens")
                return None, None, []
            if not (pfx_pos[0] < sfx_pos[0] < mid_pos):
                logger.debug("Invalid FIM token order")
                return None, None, []
            # Handle EOS token
            tail = [self.eos_id] if (self.append_eos and self.eos_id is not None) else []
            # Enhanced length management
            needed_len = len(src_ids) + len(tgt_ids) + len(tail)
            if needed_len > self.max_len:
                if self.drop_long:
                    logger.debug(f"Dropping long sample: {needed_len} > {self.max_len}")
                    return None, None, []
                # Smart truncation preserving FIM structure
                keep_for_src = self.max_len - len(tgt_ids) - len(tail)
                if keep_for_src <= 0:
                    return None, None, []
                # Calculate minimum required src length to preserve structure
                min_src_needed = len(src_ids) - pfx_pos[0]
                if keep_for_src < min_src_needed:
                    logger.debug("Cannot preserve FIM structure after truncation")
                    return None, None, []
                # Truncate from the beginning, but keep FIM structure
                start_idx = max(0, len(src_ids) - keep_for_src)
                if start_idx > pfx_pos[0]:
                    start_idx = pfx_pos[0]
                src_ids = src_ids[start_idx:]
                # Re-validate structure after truncation
                pfx_pos2 = self._find(src_ids, self.fim_prefix_id)
                sfx_pos2 = self._find(src_ids, self.fim_suffix_id)
                if not pfx_pos2 or not sfx_pos2 or not (pfx_pos2[0] < sfx_pos2[0] < len(src_ids) - 1):
                    return None, None, []
            # Encode constraint sequences
            constraint_sequences_ids = []
            if len(sample) >= 3 and sample[2]:
                constraint_sequences_ids = self.encode_constraints_sequences(sample[2])
                logger.debug(f"Encoded {len(constraint_sequences_ids)} valid constraints sequences from {len(sample[2])} candidate tokens, for a total of {sum(len(seq) for seq in constraint_sequences_ids)} constraint token IDs")
            # Assemble final sequences
            input_ids = src_ids + tgt_ids + tail
            labels = [IGNORE_INDEX] * len(src_ids) + tgt_ids
            if tail:
                labels += ([self.eos_id] if self.predict_eos else [IGNORE_INDEX])
            # Final validation
            if len(input_ids) != len(labels):
                logger.error(f"Length mismatch: input_ids={len(input_ids)}, labels={len(labels)}")
                return None, None, []
            return np.array(input_ids, dtype=np.int32), np.array(labels, dtype=np.int32), constraint_sequences_ids
        except Exception as e:
            logger.error(f"Error encoding sample: {e}")
            return None, None, []

    def encode_constraints_sequences(self, constraints_seqs: List[str]) -> List[List[int]]:
        """
        Encode each constraint as a sequence of token IDs (ragged array).
        This is required for safe constrained decoding with subword tokenization.
        """
        if not constraints_seqs:
            return []
        candidate_sequences = []
        for seq in constraints_seqs:
            # Encode as a sequence (may be multiple subwords)
            prefix_ids = self.tok.encode(JSON_STRUCTURE_TOKENS["prefix"], add_special_tokens=False)
            suffix_ids = self.tok.encode(JSON_STRUCTURE_TOKENS["suffix"], add_special_tokens=False)
            core_ids = self.tok.encode(seq, add_special_tokens=False)
            token_ids = prefix_ids + core_ids + suffix_ids
            if token_ids and token_ids[0] != self.tok.unk_token_id:
                # Append EOS if needed, at the end of each constraint sequence
                if self.append_eos and self.eos_id is not None:
                    token_ids.append(self.eos_id)
                candidate_sequences.append(token_ids)
        return candidate_sequences

    def encode_constraints(self, constraints_tokens: List[str]) -> List[int]:
        """
        Encode constraint tokens to a flat set of eligible token IDs.
        This is the correct approach for constrained decoding.
        """
        if not constraints_tokens:
            return []
        try:
            eligible_token_ids = set()
            for token in constraints_tokens:
                # Handle special FIM tokens
                if token in ['<|fim_prefix|>', '<|fim_suffix|>', '<|fim_middle|>']:
                    if token == '<|fim_prefix|>':
                        eligible_token_ids.add(self.fim_prefix_id)
                    elif token == '<|fim_suffix|>':
                        eligible_token_ids.add(self.fim_suffix_id)
                    else:
                        eligible_token_ids.add(self.fim_middle_id)
                else:
                    # For multi-character tokens that might be subword-tokenized,
                    # we need to handle them differently based on your use case
                    # Option 1: If these are meant to be complete tokens (like "assertEquals")
                    # Try to find them as single vocabulary entries first
                    token_ids = self.tok.convert_tokens_to_ids([token])
                    if token_ids and token_ids[0] != self.tok.unk_token_id:
                        # Token exists as single vocab entry
                        eligible_token_ids.add(token_ids[0])
                    else:
                        # Token needs subword tokenization - add all subword IDs
                        subword_ids = self.tok.encode(token, add_special_tokens=False)
                        if subword_ids and not (len(subword_ids) == 1 and subword_ids[0] == self.tok.unk_token_id):
                            eligible_token_ids.update(subword_ids)
                            logger.debug(f"Token '{token}' split into {len(subword_ids)} subwords")
            return sorted(list(eligible_token_ids))
        except Exception as e:
            logger.error(f"Error encoding constraints: {e}")
            return []


def build_tokenizer(
    model_name_or_path: str,
    max_length: int,
    model_revision: str,
    special_tokens: List[str],
) -> Tuple[AutoTokenizer, int, int]:
    """
    Build tokenizer with robust FIM handling for Qwen models.
    Ensures <|fim_prefix|>, <|fim_middle|>, <|fim_suffix|> are single tokens
    and registered as special tokens. Optionally adds <|fim_pad|> if desired.
    Returns (tokenizer, orig_vocab_size, new_vocab_size).
    """
    tok = AutoTokenizer.from_pretrained(
        model_name_or_path,
        revision=model_revision,
        trust_remote_code=True,
        use_fast=True,
    )

    orig_vocab_size = len(tok)

    # FIM markers (pad is optional — include it only if you actually use it)
    fim_core = ("<|fim_prefix|>", "<|fim_middle|>", "<|fim_suffix|>")
    fim_optional = ("<|fim_pad|>",)

    def _as_added(ts):
        return [
            AddedToken(
                t,
                lstrip=False,
                rstrip=False,
                single_word=False,
                normalized=False,  # critical: keep exact bytes, no NFC/space handling
                special=True,
            )
            for t in ts
        ]
    # 1) Add/mark FIM core tokens as special, ensuring they’re single tokens.
    # add_tokens is idempotent (returns 0 if already present).
    tok.add_tokens(_as_added(fim_core), special_tokens=True)
    # 2) (Optional) Add/mark <|fim_pad|>. Safe to skip if you don’t use it.
    tok.add_tokens(_as_added(fim_optional), special_tokens=True)
    # 3) Also register in additional_special_tokens (harmless if duplicates).
    tok.add_special_tokens({"additional_special_tokens": list(fim_core) + list(fim_optional)})
    # 4) Sanity check: each FIM marker must now be a single token.
    for t in fim_core:
        ids = tok.encode(t, add_special_tokens=False)
        if len(ids) != 1:
            raise ValueError(f"{t} must be a single token after add_tokens; got {ids}")
    # 5) Add any extra special tokens your pipeline wants.
    if special_tokens:
        # Use AddedToken to avoid normalization/splitting surprises
        tok.add_tokens(_as_added(special_tokens), special_tokens=True)
        tok.add_special_tokens({"additional_special_tokens": list(special_tokens)})
    # 6) Padding & max length
    if tok.pad_token is None:
        # Using EOS as pad is common for causal LMs; keep consistent with training script.
        tok.pad_token = tok.eos_token
    tok.model_max_length = max_length
    new_vocab_size = len(tok)
    return tok, orig_vocab_size, new_vocab_size


def iter_project_samples(
        file_paths: List[str],
        encoding_type: str = "jsonl",
        wrap: bool = False
) -> Iterator[Sample]:
    """
    encoding_type: 'jsonl' --> Iterates over a list of .jsonl file paths, reading each file line by line. Each line is expected to contain a
    JSON object representing a conversation between a user and an assistant.
    encoding_type: 'csv' --> Iterates over a list of .csv file paths, reading the rows of each file.
    Each row is expected to contain a sample object representing an input to the model (src), the corresponding
    target answer (tgt), and the unmask_tokens (a list of tokens that the model should consider during generation).
    The function yields the messages from these conversations as lists of dictionaries, allowing
    for efficient processing of potentially large datasets.
    """
    for p in file_paths:
        logger.debug(f"Processing file: {p}")

        try:
            if encoding_type == "jsonl":
                with io.open(p, "r", encoding="utf-8") as f:
                    for line_num, line in enumerate(f, 1):
                        try:
                            obj = orjson.loads(line)
                            msgs = obj.get("messages", obj)
                            if isinstance(msgs, list) and msgs:
                                yield (msgs, "", [])
                        except Exception as e:
                            logger.warning(f"Skipping invalid JSON at {p}:{line_num}: {e}")
                            continue

            elif encoding_type == "csv":
                try:
                    df = pd.read_csv(p, encoding="utf-8", engine="python", on_bad_lines="skip")
                except Exception as e:
                    logger.error(f"Failed to read CSV {p}: {e}")
                    continue
                # Normalize column names
                df.columns = df.columns.str.lower().str.strip()
                # Validate required columns
                required_cols = ['src', 'tgt', 'unmask_tokens']
                missing_cols = [col for col in required_cols if col not in df.columns]
                if missing_cols:
                    logger.error(f"CSV {p} missing required columns: {missing_cols}")
                    continue
                for idx, row in df.iterrows():
                    try:
                        src = str(row.get("src", "")).strip() if not pd.isna(row.get("src")) else ""
                        tgt = str(row.get("tgt", "")).strip() if not pd.isna(row.get("tgt")) else ""
                        #unmask_raw = str(row.get("unmask_tokens", "")).strip().replace("'\"'","\"\\\"\"").replace("'","\"")
                        unmask_raw = str(row.get("unmask_tokens", "")).strip()
                        unmask_tokens = []
                        # if not pd.isna(unmask_raw) and unmask_raw:
                        #     try:
                        #         # Try JSON parsing first
                        #         parsed = orjson.loads(unmask_raw) if isinstance(unmask_raw, str) else unmask_raw
                        #         if isinstance(parsed, list):
                        #             unmask_tokens = [str(token).strip() for token in parsed if str(token).strip()]
                        #         elif isinstance(parsed, str):
                        #             unmask_tokens = [parsed.strip()]
                        #         else:
                        #             unmask_tokens = [str(parsed).strip()]
                        #     except (orjson.JSONDecodeError, TypeError):
                        # Fallback to comma-separated parsing
                        unmask_tokens = [token.strip() for token in str(unmask_raw[1:-1]).split(', ') if token.strip()]
                        if "\",\"" in unmask_raw or "','" in unmask_raw and not "','" in unmask_tokens:
                            unmask_tokens.append("','")
                        # Add JSON structure tokens for constraint stages (when unmask_tokens is not empty)
                        if unmask_tokens:
                            unmask_tokens = [
                                t[1:-1] if (len(t) >= 2 and t[0] in ("'", '"') and t[-1] in ("'", '"')) else t
                                for t in unmask_tokens
                            ]
                        complete_candidate_seqs = []
                        for token in unmask_tokens:
                            #seq = JSON_STRUCTURE_TOKENS["prefix"] + token + JSON_STRUCTURE_TOKENS["suffix"]
                            #complete_candidate_seqs.append(seq)
                            complete_candidate_seqs.append(token)
                        # Only yield if both src and tgt are non-empty
                        if src and tgt:
                            yield (src, tgt, complete_candidate_seqs)
                    except Exception as e:
                        logger.warning(f"Skipping invalid row {idx} in {p}: {e}")
                        continue
        except Exception as e:
            logger.error(f"Error processing file {p}: {e}")
            continue


def sample_project_tokenized(
        files: List[str],
        target_tokens: int,             # token budget for the project
        encoding_type: str,             # 'jsonl' or 'csv'
        encoder: EncoderInterface,      # encodes one conversation -> (ids, labels, unmask_tokens)
        rng: random.Random,
        short_cut: int,
        med_cut: int,
        target_mix=(0.2, 0.4, 0.4),     # desired token share per bucket (short/med/long)
        min_len: int = 300,
        per_bucket_buf: float = 0.10,   # allow up to +10% over each bucket cap
        overflow_keep_p: float = 0.25,  # keep some over-cap items as reservoir
        early_stop_buf: float = 0.15,   # stop scanning around 115% of budget (like before)
        wrap: bool = False
) -> Tuple[List[np.ndarray], List[np.ndarray], List[List[int]], int]:
    """
    Samples datapoints from a given project, according to a distribution determined by the conversation lengths, up to
    the limit imposed by the `target_tokens` cap assigned to the project.
    Returns: (input_ids_list, labels_list, constraint_ids_list, len_constraint_ids_list, total_tokens)
    """
    # `target_tokens` define the maximum number of tokens assigned to the project
    # The total number of tokens is distributed in 3 buckets, considering samples
    # of `small`, `medium`, and `large` dimension. The `target_mix` determines the
    # distribution of tokens cap assigned to each bucket. The dimension of the sample
    # is determined by the `short_cut` and `med_cut` values that represents the thresholds
    # between `short` and `medium` samples, and `medium` and `large` samples
    cap = [int(target_tokens * p) for p in target_mix]
    cap_plus = [int(c * (1 + per_bucket_buf)) for c in cap]
    # Tokens counter per bucket
    tok_sum = [0, 0, 0]
    soft_total = 0
    # Buckets of samples (it initially accept values <= the soft cap, per bucket)
    soft_buckets = [[], [], []]
    # Buckets of overflow samples (over-cap samples)
    overflow = [[], [], []]
    # Iterate over the samples of the project
    for sample in iter_project_samples(files, encoding_type, wrap):
        # Encode the messages in the conversation and retrieve the encoded `input_ids` and `label_ids` to
        # fine-tune the model
        ids, lbl, constraint_seq_ids = encoder.encode(sample)
        # Discard empty samples
        if ids is None:
            continue
        # Count the tokens within the input
        L = int(len(ids))
        # Filter ultra-short samples (often noise)
        if L < min_len:
            continue
        # Determine the bucket of the sample
        b = bucket_from_len(L, short_cut, med_cut)
        # Prefer filling deficit buckets, but keep additional samples until soft cap
        if tok_sum[b] <= cap_plus[b]:
            # Append the candidate to the corresponding soft bucket (save both the encoded `input_ids`,
            # `label_ids` and tokens length `L` of the `input_ids`
            soft_buckets[b].append((ids, lbl, constraint_seq_ids, L))
            # Update the total counter of tokens of the bucket to which the sample belongs
            tok_sum[b] += L
            # Update the soft total
            soft_total += L
        else:
            # Keep some overflow as backfill reservoir (helps skewed corpora)
            if rng.random() < overflow_keep_p:
                overflow[b].append((ids, lbl, constraint_seq_ids, L))
        # Early stop: once pools roughly cover the whole project budget, stop scanning
        if soft_total >= int(target_tokens * (1 + early_stop_buf)):
            break
    # Iterate over the three buckets and the overflows backfill reservoir and shuffle them for variety
    for k in range(len(soft_buckets)):
        rng.shuffle(soft_buckets[k])
        rng.shuffle(overflow[k])
    # Define final lists of selected samples (input_ids, labels).
    # Written counts the total number of tokens selected, overall, and per bucket (`written_by_bucket`)
    sel_ids, sel_lbls, sel_constraint_seq_ids, written = [], [], [], 0
    written_by_bucket = [0, 0, 0]
    # Keep track of unused samples
    leftovers = []
    # Iterate over the buckets to select the samples
    for k in range(len(soft_buckets)):
        # Iterate over the samples in the current bucket
        for _ids, _lbl, _constraint_seq_ids, _L in soft_buckets[k]:
            # If the limit of tokens assigned to the project has been overcome, exit the loop
            if written >= target_tokens:
                break
            # If the soft limit of token for the current bucket has not been overcome, add the sample
            # and update the relative and absolute counters
            if written_by_bucket[k] <= cap_plus[k]:
                sel_ids.append(_ids)
                sel_lbls.append(_lbl)
                sel_constraint_seq_ids.append(_constraint_seq_ids)
                written += _L
                written_by_bucket[k] += _L
            else:
                leftovers.append((_ids, _lbl, _constraint_seq_ids, _L))
        # If the limit of tokens assigned to the project has been overcome, exit the loop even if one or more
        # buckets have not been processed yet
        if written >= target_tokens:
            break

    # Fill remaining capacity with overflow
    if written < target_tokens:
        for k in range(len(soft_buckets)):
            leftovers.extend(overflow[k])
        # Shuffle and greedily fill to target (allow tiny overshoot for small samples)
        rng.shuffle(leftovers)
        # Iterate over the leftover samples and add them up to the limit of tokens assigned to project, if any
        for _ids, _lbl, _constraint_seq_ids, _L in leftovers:
            if written < target_tokens:
                sel_ids.append(_ids)
                sel_lbls.append(_lbl)
                sel_constraint_seq_ids.append(_constraint_seq_ids)
                written += _L
            if written >= target_tokens:
                break
    # Returns the selected samples
    return sel_ids, sel_lbls, sel_constraint_seq_ids, written


# ... (rest of the helper functions remain the same)
def read_split_csv(path: str) -> Dict[str, Dict]:
    """
    Reads the original csv file containing the list of projects to process (`project_id`), the number of oracles
    retrieved from each project (`num_oracles`) and the dimension of the project (3 possible values admitted: `Small`,
    `Medium`, `Large`.
    Returns a corresponding dictionary containing the information read, in the following format:
        {
            project_id: {
                'num_oracles': int,
                'size_bin': 'Small'|'Medium'|'Large'
            }
        }
    """
    out = {}
    with open(path, "r", encoding="utf-8") as f:
        rdr = csv.DictReader(f)
        for r in rdr:
            pid = r["project_id"]
            out[pid] = {
                "num_oracles": int(r["num_oracles"]),
                "size_bin": r["size_bin"],
            }
    return out


def find_project_files_by_ext(data_dir: str, pids: List[str], ext: str = "jsonl") -> Dict[str, List[str]]:
    """
    Searches the files containing the datapoints corresponding to the given project ids (`pids`), and collect them.
    """
    # Collect the list of files representing the dataset, from the `data_dir`
    files = glob.glob(os.path.join(data_dir, f"*.{ext}"))
    # Initialize the dictionary of projects files links
    by = {pid: [] for pid in pids}
    # Iterate over the list of `.jsonl` files within `data_dir`
    for f in files:
        # Get the name of the current file
        name = os.path.basename(f)
        # Extract the project identifier from the name of the current file
        pid = name.replace("oracles-datapoints-", "").split("-")[0]
        # If the project id is in the list of target `pids` append the file to the list of the corresponding `pid`
        # in the dictionary
        if pid in by:
            by[pid].append(f)
    for k in by:
        by[k] = sorted(by[k])
    return by


def split_by_bins(meta: Dict[str, Dict]) -> Dict[str, List[str]]:
    """
    Split the projects processed from `.csv` in bins, representing their dimension.
    """
    bins = {"Small": [], "Medium": [], "Large": []}
    for pid, m in meta.items():
        b = m["size_bin"]
        # If the size label is not recognized, the dimension is set to `Medium`
        if b not in bins:
            b = "Medium"
        bins[b].append(pid)
    for k in bins:
        bins[k].sort()
    return bins


def allocate_bin_budgets(meta: Dict[str, Dict], total_token_budget: int, mode: str = "sqrt") -> Dict[str, int]:
    """
    Starting from a `total_token_budget` number of tokens, allocate a token budget to each bin in the dataset,
    according to the given `mode`.
    `mode` ∈ {'proportional','equal','sqrt'}
        - proportional: by total oracles per bin
        - equal: equal tokens per bin
        - sqrt: by sqrt(total oracles per bin)
    Returns a dictionary where the key is the dimension of the bin (`Small`, `Medium`, `Large`) and the value
    is the token budget assigned to the bin, according to the given `mode`.
    """
    # Divide the projects, collecting them into `Small`, `Medium`, and `Large` bins
    bins = split_by_bins(meta)
    # Initialize a dictionary containing the total number of oracles within each bin collection
    sizes = {}
    # Iterate over each bin collection (b is the dimension, pids is the list of projects of that dimension)
    for b, pids in bins.items():
        # Compute the total of oracles within the collection, summing the number of oracles within each project
        sizes[b] = sum(meta[pid]["num_oracles"] for pid in pids)
    # If `mode` is `equal`, each bin contribute to the dataset with the same weight
    if mode == "equal":
        w = {b: 1.0 for b in bins}
    # If `mode` is `proportional`, each bin contribute to the dataset with a weight corresponding
    # to the total number of oracles within it
    elif mode == "proportional":
        w = {b: float(sizes[b]) for b in bins}
    else:
        w = {b: math.sqrt(max(1, sizes[b])) for b in bins}
    sw = sum(w.values()) or 1.0
    # Assign the total token budget to each bin, according to the weight previously computed
    return {b: int(total_token_budget * (w[b] / sw)) for b in bins}


def allocate_project_budgets(
        meta: Dict[str, Dict],
        bin_tokens: Dict[str, int],
        alpha: float = 0.5,
        min_tokens_per_project: int = 8192
) -> Dict[str, int]:
    """
    Inside each bin, distribute its budget across projects using weight = (num_oracles)^alpha.
    alpha:
        - 0   -> uniform per project
        - 1.0 -> proportional to num_oracles
        - 0.5 -> √-scaling (robust default)
    Return the dictionary of token budget for each project in the bin
    """
    # Divide the projects, collecting them into `Small`, `Medium`, and `Large` bins
    bins = split_by_bins(meta)
    # Initialize a dictionary containing the budget of tokens assigned to each project within the bin,
    # according to their contribution to the total number of oracles within the bin.
    budgets = {}
    # Iterate over each bin collection (b is the dimension, pids is the list of projects of that dimension)
    for b, pids in bins.items():
        # If the bin does not contain projects skip the bin
        if not pids:
            continue
        weights = [(pid, max(1, meta[pid]["num_oracles"]) ** alpha) for pid in pids]
        sw = sum(w for _, w in weights) or 1.0
        # Iterate over each tuple of project id (`pid`) and corresponding weight (`w`)
        for pid, w in weights:
            # Compute the number of tokens to assign to the project, given the total budget assigned
            # to the bin and the weights computed for the given project
            share = int(bin_tokens[b] * (w / sw))
            budgets[pid] = max(share, min_tokens_per_project)

    return budgets


def bucket_from_len(L: int, s_cut: int, m_cut: int) -> int:
    if L <= s_cut:
        return 0
    if L <= m_cut:
        return 1
    return 2


def build_split(
        split_name: str,
        encoding_type: str,
        pids_path: str,
        data_dir: str,
        out_dir: str,
        model_name_or_path: str,
        model_revision: str,
        max_length: int,
        special_tokens: List[str],
        total_token_budget: int,
        bin_allocation: str,
        alpha: float,
        drop_long: bool,
        samples_per_json_file: int,
        target_mix: Tuple[float, float, float],
        short_cut_frac: float,
        med_cut_frac: float,
        seed: int,
        wrap: bool = False
):
    # Initalize pseudo-random with seed for reproducibility
    rng = random.Random(seed)
    # Get the tokenizer with FIM tokens
    tok, tok_vocab_len_before, tok_vocab_len_after = build_tokenizer(
        model_name_or_path,
        max_length,
        model_revision,
        special_tokens
    )

    # Initialize encoder
    # if encoding_type == "jsonl":
    #     enc = ChatEncoder(tok, max_len=max_length, only_last_turn_loss=True, drop_long=drop_long)
    # else:
    #     enc = PairEncoder(tok, max_len=max_length, drop_long=drop_long, append_eos=True)
    enc = PairEncoder(tok, max_len=max_length, drop_long=drop_long, append_eos=True)

    # Read csv with the identifiers of projects to process, and the relative metadata
    meta = read_split_csv(pids_path)
    # Get the list of project identifiers
    pids = sorted(meta.keys())

    files_by_pid = find_project_files_by_ext(data_dir, pids, encoding_type)
    # Allocate tokens budget for each bin (`Small`, `Medium`, `Large`, determined by the total number
    # of oracles in each project and the policy applied - `mode`)
    bin_tokens = allocate_bin_budgets(meta, total_token_budget, mode=bin_allocation)
    # Allocate the total number of tokens assigned to each project
    proj_budget = allocate_project_budgets(meta, bin_tokens, alpha=alpha, min_tokens_per_project=max(max_length, 4096))
    # Define thresholds to consider dimension of conversations within project (`small`, `medium`, `large` conversations)
    short_cut = int(max_length * short_cut_frac) # e.g., 0.33 * 4096
    med_cut = int(max_length * med_cut_frac)     # e.g., 0.66 * 4096
    # Collect metadata
    meta_out = {
        "split": split_name,
        "model_name_or_path": model_name_or_path,
        "model_revision": model_revision,
        "max_length": max_length,
        "bin_allocation": bin_allocation,
        "alpha": alpha,
        "token_budget": total_token_budget,
        "target_mix": target_mix,
        "length_cuts": {"short": short_cut, "medium": med_cut},
        "pad_token_id": tok.pad_token_id,
        "eos_token_id": tok.eos_token_id,
        "special_tokens_map": tok.special_tokens_map,
        "vocab_size_before": tok_vocab_len_before,
        "vocab_size": tok_vocab_len_after,
        "special_token_ids": {t: tok(t).input_ids[0] for t in (special_tokens + ["<|fim_prefix|>", "<|fim_suffix|>", "<|fim_middle|>"])},
        "wrap": wrap,
        "drop_long": drop_long,
        "num_projects": len(pids),
        "projects": pids
    }
    # Define path where to save tokenizer
    tok_dir = os.path.join(out_dir, "tokenizer")
    # Create tokenizer and output path dirs, if they do not exist
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(tok_dir, exist_ok=True)
    # Save tokenizer in `tok_dir`
    tok.save_pretrained(tok_dir)
    # Save metadata to output dir
    with open(os.path.join(out_dir, f"{split_name}.meta.json"), "w") as f:
        json.dump(meta_out, f, indent=2)
    with open(os.path.join(out_dir, "base_model.json"), "w") as bf:
        json.dump({
            "base_model_name_or_path": model_name_or_path,
            "base_model_revision": model_revision or "main"
        }, bf, indent=2)
    # Process samples
    writer = JSONWriter(out_dir, split_name, samples_per_file=samples_per_json_file)
    total_written = 0
    per_project = {}
    # Iterate over each project and process each of them
    for pid in pids:
        # Get the budget cap of tokens assigned to the project
        tokens_cap = proj_budget.get(pid, 0)
        # Get the dataset files related to the project, containing the samples of the project
        files = files_by_pid.get(pid, [])
        # Skip project if no budget is assigned or the projects does not have samples
        if tokens_cap <= 0 or not files:
            continue
        # Get samples with constraints
        ids_list, lbl_list, constraint_seq_ids_list, got = sample_project_tokenized(
            files,
            tokens_cap,
            encoding_type,
            enc,
            rng,
            short_cut,
            med_cut,
            target_mix=target_mix,
            wrap=wrap
        )
        # Write samples with constraints
        for ids, lbl, constraint_seq_ids in zip(ids_list, lbl_list, constraint_seq_ids_list):
            writer.write(ids, lbl, constraint_seq_ids)
        # Update written tokens per project and in total
        total_written += got
        per_project[pid] = got
        # Log stats
        print(f"[{split_name}] {pid} ({meta[pid]['size_bin']}) -> {got:,} tokens; total={total_written:,}")
    # Close writer at the end of the process
    writer.close()
    # Save stats related to the sampling and encoding
    with open(os.path.join(out_dir, f"{split_name}.per_project_tokens.json"), "w") as f:
        json.dump(per_project, f, indent=2)
    print(f"[{split_name}] DONE. total_tokens={total_written:,}")


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model_name_or_path", required=True, help="Path to pretrained model or model identifier from huggingface.co")
    ap.add_argument("--model_revision", type=str, default=None, help="HF revision (commit hash or tag) to pin the base tokenizer.")
    ap.add_argument("--encoding_type", choices=["jsonl", "csv"], default="jsonl", help="Type of files to process (jsonl with chat messages or csv with src/tgt pairs).")
    ap.add_argument("--data_dir", required=True, help="Path to data directory where JSON files are stored.")
    ap.add_argument("--train_csv", required=True, help="Path to training CSV (the csv contains the list of project identifers to use for training).")
    ap.add_argument("--val_csv", required=True, help="Path to validation CSV (the csv contains the list of project identifers to use for validation).")
    ap.add_argument("--out_dir", required=True, help="Path to output directory.")
    ap.add_argument("--max_length", type=int, default=8192, help="Maximum length of tokens.")
    ap.add_argument("--start_id", type=str, default="<|im_start|>", help="Opening token for a message block (fallback formatter).")
    ap.add_argument("--end_id", type=str, default="<|im_end|>", help="Closing token for a message block (fallback formatter).")
    ap.add_argument("--train_token_budget", type=int, default=2_000_000_000, help="Token budget for training.")
    ap.add_argument("--val_token_budget", type=int, default=40_000_000, help="Token budget for validation.")
    ap.add_argument("--bin_allocation", choices=["proportional", "equal", "sqrt"], default="sqrt", help="How to allocate tokens across Small/Medium/Large bins.")
    ap.add_argument("--alpha", type=float, default=0.5, help="Within-bin per-project weighting exponent on num_oracles.")
    ap.add_argument("--target_mix", type=str, default="0.4,0.4,0.2", help="Short,Medium,Long token share per project (comma).")
    ap.add_argument("--short_cut_frac", type=float, default=0.33, help="Short ≤ frac*max_length.")
    ap.add_argument("--med_cut_frac", type=float, default=0.66, help="Medium ≤ frac*max_length.")
    ap.add_argument("--samples_per_json_file", type=int, default=1000, help="Number of samples per JSON file.")
    ap.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility.")
    ap.add_argument("--train_drop_long", action="store_true", help="Whether to drop long tokens in training bins.")
    ap.add_argument("--val_drop_long", action="store_true", help="Whether to drop long tokens in validation bins.")
    ap.add_argument("--wrap", action="store_true", help="Whether to wrap each message in [token][/token] format.")
    return ap.parse_args()


def resolve_snapshot(model_name_or_path: str, model_revision: Optional[str]):
    # Resolve snapshot (commit SHA) for reproducibility
    # Check if the `model_name_or_path` links to a local directory
    if os.path.isdir(model_name_or_path):
        # Set the `resolved_path` to the local directory and the `used_revision` to `local`
        resolved_path = os.path.abspath(model_name_or_path)
        used_revision = "local"
    else:
        # Set the `resolved_path` to the value returned by the api
        resolved_path = snapshot_download(repo_id=model_name_or_path, revision=model_revision)
        # Get the commit SHA of the snapshot
        used_revision = Path(resolved_path).name
    return resolved_path, used_revision


def main():
    """Enhanced main function with better error handling and logging."""
    try:
        args = parse_args()

        # Validate arguments
        mix = tuple(float(x) for x in args.target_mix.split(","))
        assert len(mix) == 3 and abs(sum(mix) - 1.0) < 1e-6, "target_mix must sum to 1.0"
        assert 0.0 < args.short_cut_frac < args.med_cut_frac < 1.0, "Invalid cut fractions"

        logger.info("Starting dataset tokenization process...")
        logger.info(f"Model: {args.model_name_or_path}")
        logger.info(f"Encoding type: {args.encoding_type}")
        logger.info(f"Max length: {args.max_length}")

        resolved_path, used_revision = resolve_snapshot(args.model_name_or_path, args.model_revision)

        # Determine special tokens based on encoding type
        special_tokens = [args.start_id, args.end_id] if args.encoding_type == "jsonl" else []

        # Build splits with enhanced error handling
        try:
            build_split(
                "train", args.encoding_type, args.train_csv, args.data_dir, args.out_dir,
                resolved_path, used_revision, args.max_length, special_tokens,
                args.train_token_budget, args.bin_allocation, args.alpha,
                args.train_drop_long, args.samples_per_json_file, mix,
                args.short_cut_frac, args.med_cut_frac, args.seed, args.wrap
            )
        except Exception as e:
            logger.error(f"Failed to build train split: {e}")
            raise

        try:
            build_split(
                "val", args.encoding_type, args.val_csv, args.data_dir, args.out_dir,
                resolved_path, used_revision, args.max_length, special_tokens,
                args.val_token_budget, args.bin_allocation, args.alpha,
                args.val_drop_long, max(10_000_000, args.samples_per_json_file // 5), mix,
                args.short_cut_frac, args.med_cut_frac, args.seed + 1, args.wrap
            )
        except Exception as e:
            logger.error(f"Failed to build val split: {e}")
            raise

        logger.info("Dataset tokenization completed successfully!")

    except Exception as e:
        logger.error(f"Fatal error in main: {e}")
        raise


if __name__ == "__main__":
    main()
