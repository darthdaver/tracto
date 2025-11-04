#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Inference prompt encoder - creates tokenized JSON files in the same format as training.
Reads a CSV row and produces input_ids, labels, constraints like encode_json.py does.
"""

import argparse
import csv
import json
import logging
import os
import ast
from typing import List, Optional, Tuple
import numpy as np
from transformers import AutoTokenizer

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# Constants (same as training)
IGNORE_INDEX = -100

JSON_STRUCTURE_TOKENS = {
    "prefix": "{\"choice\":\"",
    "suffix": "\"}"
}

LITERALS = [
    "DECIMAL_LITERAL", "HEX_LITERAL", "OCT_LITERAL", "BINARY_LITERAL",
    "FLOAT_LITERAL", "HEX_FLOAT_LITERAL", "BOOL_LITERAL",
    "CHAR_LITERAL", "STRING_LITERAL", "LAMDA_PARAM"
]


class InferenceEncoder:
    """
    Encodes a single CSV row into the same format used during training.
    """
    def __init__(self, tokenizer: AutoTokenizer, max_length: int = 8192):
        self.tok = tokenizer
        self.max_length = max_length
        # Get FIM token IDs
        self.fim_prefix_id = self._get_special_token_id("<|fim_prefix|>")
        self.fim_suffix_id = self._get_special_token_id("<|fim_suffix|>")
        self.fim_middle_id = self._get_special_token_id("<|fim_middle|>")
        self.eos_id = getattr(tokenizer, "eos_token_id", None)
        if None in (self.fim_prefix_id, self.fim_suffix_id, self.fim_middle_id):
            raise ValueError("FIM tokens not found in tokenizer vocabulary")

    def _get_special_token_id(self, token: str) -> Optional[int]:
        """
        Get token ID for special tokens.
        Uses encode() which works correctly for added special tokens.
        """
        try:
            # Use encode (works for special tokens as shown in your test)
            ids = self.tok.encode(token, add_special_tokens=False)

            if ids and len(ids) == 1:
                token_id = ids[0]
                # Verify it's not the unknown token
                if token_id != self.tok.unk_token_id:
                    return token_id

            # Fallback: if encode returned empty or multiple tokens,
            # try added_tokens_encoder directly
            if hasattr(self.tok, 'added_tokens_encoder') and token in self.tok.added_tokens_encoder:
                return self.tok.added_tokens_encoder[token]

            logger.warning(f"Token {token} not found in tokenizer")
            return None

        except Exception as e:
            logger.error(f"Failed to get token ID for {token}: {e}")
            return None

    def _tokenize_plain(self, text: str) -> List[int]:
        """
        Tokenize text using the same logic as training.
        Handles JSON structure tokens specially to ensure consistent tokenization.
        """
        if not text:
            return []
        try:
            # If target JSON structure (selection stage)
            if text.startswith(JSON_STRUCTURE_TOKENS["prefix"]):
                prefix_ids = self.tok.encode(
                    JSON_STRUCTURE_TOKENS["prefix"],
                    add_special_tokens=False
                )
                suffix_ids = self.tok.encode(
                    JSON_STRUCTURE_TOKENS["suffix"],
                    add_special_tokens=False
                )
                core = text.replace(JSON_STRUCTURE_TOKENS["prefix"], "").replace(
                    JSON_STRUCTURE_TOKENS["suffix"], ""
                )
                core_ids = self.tok.encode(core, add_special_tokens=False)
                return prefix_ids + core_ids + suffix_ids
            return self.tok.encode(text, add_special_tokens=False)
        except Exception as e:
            logger.error(f"Tokenization failed for text length {len(text)}: {e}")
            return []

    def _encode_constraint_sequences(self, constraint_tokens: List[str]) -> List[List[int]]:
        """
        Encode each constraint as a sequence of token IDs (ragged array).
        Same logic as training: wraps each candidate in JSON structure.
        """
        if not constraint_tokens:
            return []
        candidate_sequences = []
        for token in constraint_tokens:
            # Build complete JSON structure for this candidate
            complete_seq = JSON_STRUCTURE_TOKENS["prefix"] + token + JSON_STRUCTURE_TOKENS["suffix"]
            # Tokenize using the special method
            token_ids = self._tokenize_plain(
                JSON_STRUCTURE_TOKENS["prefix"] + token + JSON_STRUCTURE_TOKENS["suffix"]
            )
            if token_ids and token_ids[0] != self.tok.unk_token_id:
                candidate_sequences.append(token_ids)
        logger.debug( f"Encoded {len(candidate_sequences)} constraint sequences from {len(constraint_tokens)} candidates")
        return candidate_sequences

    def encode_csv_row(
            self,
            src: str,
            unmask_tokens: List[str]
    ) -> Tuple[Optional[np.ndarray], Optional[np.ndarray], Optional[List[List[int]]], int]:
        """
        Encode a single CSV row into training format.
        Args:
            src: Source text (with FIM markers)
            unmask_tokens: List of candidate tokens for constraints
        Returns:
            (input_ids, labels, constraints, length) or (None, None, None, 0) if invalid
        """
        try:
            # Tokenize source and target
            src_ids = self._tokenize_plain(src)
            tgt_ids = []

            if not src_ids:
                logger.debug("Skipping: empty source tokenization")
                return None, None, None, 0
            # Verify FIM structure
            if src_ids[-1] != self.fim_middle_id:
                logger.debug("Skipping: src doesn't end with <|fim_middle|>")
                return None, None, None, 0
            # Encode constraints
            constraint_sequences = self._encode_constraint_sequences(unmask_tokens)
            # Build input_ids
            tail = [self.eos_id] if self.eos_id is not None else []
            input_ids = src_ids + tgt_ids + tail
            # Build labels (IGNORE_INDEX for src, actual tokens for tgt)
            labels = [IGNORE_INDEX] * len(src_ids) + tgt_ids
            if tail:
                labels += [self.eos_id]  # Predict EOS
            # Validate
            if len(input_ids) != len(labels):
                logger.error(f"Length mismatch: input_ids={len(input_ids)}, labels={len(labels)}")
                return None, None, None, 0
            if len(input_ids) > self.max_length:
                logger.warning(f"Sample exceeds max_length ({len(input_ids)} > {self.max_length}), truncating")
                input_ids = input_ids[:self.max_length]
                labels = labels[:self.max_length]
            return (
                np.array(input_ids, dtype=np.int32),
                np.array(labels, dtype=np.int32),
                constraint_sequences,
                len(input_ids)
            )
        except Exception as e:
            logger.error(f"Error encoding sample: {e}")
            return None, None, None, 0

    def load_csv_row(
            self,
            csv_path: str,
            row_id: Optional[str] = None
    ) -> Tuple[str, str, str, List[str]]:
        """
        Load a single row from CSV.

        Returns:
            (id, src, tgt, unmask_tokens)
        """
        with open(csv_path, 'r', encoding='utf-8') as f:
            reader = csv.DictReader(f)
            rows = list(reader)
        if not rows:
            raise ValueError(f"CSV file {csv_path} is empty")
        # Select row
        if row_id:
            matching_rows = [r for r in rows if r.get('id') == row_id]
            if not matching_rows:
                raise ValueError(f"Row with id={row_id} not found")
            row = matching_rows[0]
        else:
            row = rows[0]
        # Extract fields
        row_id_val = row.get('id', 'unknown').strip()
        src = row.get('src', '').strip()
        tgt = row.get('tgt', '').strip()
        unmask_raw = row.get('unmask_tokens', '').strip()
        # Parse unmask_tokens
        unmask_tokens = self._parse_unmask_tokens(unmask_raw)
        return row_id_val, src, tgt, unmask_tokens

    def _parse_unmask_tokens(self, unmask_raw: str) -> List[str]:
        """
        Parse unmask_tokens field from CSV using the SAME logic as training.
        Handles formats like: "['token1', 'token2', ...]"
        """
        if not unmask_raw:
            return []
        try:
            # Ensure it's a string
            unmask_raw = str(unmask_raw).strip()
            if not unmask_raw:
                return []
            # Step 1: Remove outer brackets [...]
            # Split on ', ' (comma + space) exactly like training
            tokens = [
                token.strip()
                for token in str(unmask_raw[1:-1]).split(', ')
                if token.strip()
            ]
            # Step 2: Special handling for comma token
            # Check if ',' appears as a delimiter in the original string
            if '","' in unmask_raw or "','" in unmask_raw:
                if "','" not in tokens:
                    tokens.append("','")
            # Step 3: Remove quotes from each token
            # Only if BOTH first and last chars are quotes
            if tokens:
                tokens = [
                    t[1:-1] if (len(t) >= 2 and t[0] in ("'", '"') and t[-1] in ("'", '"')) else t
                    for t in tokens
                ]
            return tokens
        except Exception as e:
            logger.warning(f"Failed to parse unmask_tokens: {e}")
            return []

    def save_json(
            self,
            output_path: str,
            input_ids: np.ndarray,
            labels: np.ndarray,
            constraints: List[List[int]],
            length: int,
            split: str = "inference",
            row_id: str = "unknown"
    ):
        """
        Save encoded sample in the same JSON format as training.
        """
        os.makedirs(os.path.dirname(output_path) or '.', exist_ok=True)
        data = {
            "metadata": {
                "split": split,
                "file_id": 0,
                "num_samples": 1,
                "total_tokens": length,
                "row_id": row_id
            },
            "samples": [
                {
                    "input_ids": input_ids.tolist(),
                    "labels": labels.tolist(),
                    "constraints": constraints,
                    "length": length
                }
            ]
        }
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
        logger.info(f"Saved encoded sample to {output_path}")
        logger.info(f"  - Length: {length} tokens")
        logger.info(f"  - Constraints: {len(constraints)} sequences")


def main():
    parser = argparse.ArgumentParser(
        description="Encode a CSV row for inference using the same format as training"
    )
    parser.add_argument(
        "--tokenizer-name-or-path",
        type=str,
        required=True,
        help="Tokenizer model name or path"
    )
    parser.add_argument(
        "--csv-path",
        type=str,
        required=True,
        help="Path to CSV file (id, src, tgt, unmask_tokens)"
    )
    parser.add_argument(
        "--row-id",
        type=str,
        default=None,
        help="ID of specific row to encode (default: first row)"
    )
    parser.add_argument(
        "--output-path",
        type=str,
        required=True,
        help="Output JSON file path"
    )
    parser.add_argument(
        "--phase",
        type=str,
        required=True,
        help="The phase of the model: 'choice' or 'emit'"
    )
    parser.add_argument(
        "--max-length",
        type=int,
        default=8192,
        help="Maximum sequence length"
    )
    parser.add_argument(
        "--include-target",
        action="store_true",
        help="Include target in input_ids (for validation/testing)"
    )
    parser.add_argument(
        "--literal",
        type=str,
        default="STRING_LITERAL",
        help="Literal token type (default: STRING_LITERAL)"
    )
    parser.add_argument(
        "--split-name",
        type=str,
        default="inference",
        help="Split name for metadata (default: inference)"
    )

    args = parser.parse_args()

    # Load tokenizer
    logger.info(f"Loading tokenizer from {args.tokenizer_name_or_path}")
    tokenizer = AutoTokenizer.from_pretrained(
        args.tokenizer_name_or_path,
        trust_remote_code=True,
        use_fast=True
    )
    # Create encoder
    encoder = InferenceEncoder(tokenizer, max_length=args.max_length)
    # Load CSV row
    logger.info(f"Loading row from {args.csv_path}")
    row_id, src, _, unmask_tokens = encoder.load_csv_row(args.csv_path, args.row_id)

    logger.info(f"Encoding row: {row_id}")
    logger.info(f"  - Source length: {len(src)} chars")
    logger.info(f"  - Candidates: {len(unmask_tokens)}")

    # Encode
    input_ids, labels, constraints, length = encoder.encode_csv_row(
        src=src,
        unmask_tokens=unmask_tokens
    )

    if input_ids is None:
        logger.error("Failed to encode sample")
        return 1

    # Save
    encoder.save_json(
        output_path=args.output_path,
        input_ids=input_ids,
        labels=labels,
        constraints=constraints,
        length=length,
        split=args.split_name,
        row_id=row_id
    )
    logger.info("Done!")
    return 0


if __name__ == "__main__":
    exit(main())
