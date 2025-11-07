#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Simplified tokenized SFT corpus for full oracle prediction.

- Uses train/val CSVs with columns: project_id,num_oracles,size_bin
- Allocates token budgets per bin and per project (√-scaling)
- Encodes FIM-style pairs: prompt with FIM tokens -> full oracle
- Assistant-only supervision (IGNORE_INDEX=-100)
- Length buckets by token length after encoding: short/med/long
- JSON output: {split}.{shard}.json with input_ids, labels

Example:
python encode_oracle.py \
  --model_name_or_path Qwen/Qwen2.5-Coder-14B-Instruct \
  --data_dir /path/csv \
  --train_csv /path/splits/train_projects.csv \
  --val_csv /path/splits/val_projects.csv \
  --out_dir /path/tokenized \
  --train_token_budget 2000000000 \
  --val_token_budget 40000000 \
  --max_length 4096 \
  --bin_allocation sqrt \
  --alpha 0.5 \
  --target_mix 0.4,0.4,0.2 \
  --samples_per_json_file 1000 \
  --train_drop_long
"""

import io, csv, glob, math, random, argparse, logging, os, json
from typing import Dict, List, Tuple, Optional, Iterator
import pandas as pd
import numpy as np
from transformers import AutoTokenizer, AddedToken
from huggingface_hub import snapshot_download
from pathlib import Path

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

# --------------------------
# Constants
# --------------------------
IGNORE_INDEX = -100


class JSONWriter:
    """
    JSON writer for input_ids and labels.
    Saves data in JSON format for easy loading.
    """

    def __init__(self, out_dir: str, split: str, samples_per_file: int = 1000):
        """
        Initialize JSON writer.

        Args:
            out_dir: Output directory
            split: Split name (train/val)
            samples_per_file: Number of samples per JSON file
        """
        os.makedirs(out_dir, exist_ok=True)
        self.out_dir = out_dir
        self.split = split
        self.samples_per_file = samples_per_file
        self.file_id = 0
        self.current_samples = []
        self.total_samples = 0
        self.total_tokens = 0

    def write(self, input_ids: np.ndarray, labels: np.ndarray) -> None:
        """
        Write a sample to the current JSON batch.

        Args:
            input_ids: np.ndarray of int32, shape (L,)
            labels: np.ndarray of int32, shape (L,)
        """
        try:
            # Convert numpy arrays to lists for JSON serialization
            sample_data = {
                "input_ids": input_ids.tolist() if isinstance(input_ids, np.ndarray) else input_ids,
                "labels": labels.tolist() if isinstance(labels, np.ndarray) else labels,
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
                f"Closed JSON writer for {self.split}: {self.total_samples} samples, "
                f"{self.total_tokens} tokens across {self.file_id} files"
            )
        except Exception as e:
            logger.error(f"Error closing JSON writer: {e}")
            raise


class FIMOracleEncoder:
    """
    Encoder for FIM-style full oracle prediction:
    - src = prompt including <|fim_prefix|>, <|fim_suffix|>, <|fim_middle|>
    - tgt = complete oracle (e.g., "assertInstanceOf(HttpClientExecutor.class, executor);")

    Loss is computed only on tgt (+ optional EOS).
    """

    def __init__(
        self,
        tok: AutoTokenizer,
        max_len: int,
        drop_long: bool = False,
        append_eos: bool = True,
        predict_eos: bool = True,
    ):
        self.tok = tok
        self.max_len = max_len
        self.drop_long = drop_long
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
        """Tokenize plain text without special tokens."""
        if not text:
            return []
        try:
            return self.tok.encode(text, add_special_tokens=False)
        except Exception as e:
            logger.error(f"Tokenization failed for text length {len(text)}: {e}")
            return []

    def _find(self, ids: List[int], tid: int) -> List[int]:
        """Find positions of token id in sequence."""
        return [i for i, t in enumerate(ids) if t == tid]

    def encode(self, sample: Tuple[str, str]) -> Tuple[Optional[np.ndarray], Optional[np.ndarray]]:
        """
        Encode a (src, tgt) pair.

        Args:
            sample: Tuple of (prompt_with_FIM, oracle_text)

        Returns:
            Tuple of (input_ids, labels) or (None, None) if invalid
        """
        try:
            src, tgt = sample

            # Validate inputs
            if not src or not tgt:
                logger.debug("Skipping empty src or tgt")
                return None, None

            src_ids = self._tokenize_plain(src)
            tgt_ids = self._tokenize_plain(tgt)

            if not src_ids or not tgt_ids:
                logger.debug("Skipping sample with empty tokenization")
                return None, None

            # Validate FIM structure
            if src_ids[-1] != self.fim_middle_id:
                logger.debug("Skipping src without <|fim_middle|> at end")
                return None, None

            # Verify FIM structure
            pfx_pos = self._find(src_ids, self.fim_prefix_id)
            sfx_pos = self._find(src_ids, self.fim_suffix_id)
            mid_pos = len(src_ids) - 1

            if not pfx_pos or not sfx_pos:
                logger.debug("Missing FIM prefix or suffix tokens")
                return None, None

            if not (pfx_pos[0] < sfx_pos[0] < mid_pos):
                logger.debug("Invalid FIM token order")
                return None, None

            # Handle EOS token
            tail = [self.eos_id] if (self.append_eos and self.eos_id is not None) else []

            # Length management
            needed_len = len(src_ids) + len(tgt_ids) + len(tail)

            if needed_len > self.max_len:
                if self.drop_long:
                    logger.debug(f"Dropping long sample: {needed_len} > {self.max_len}")
                    return None, None

                # Smart truncation preserving FIM structure
                keep_for_src = self.max_len - len(tgt_ids) - len(tail)

                if keep_for_src <= 0:
                    return None, None

                # Calculate minimum required src length to preserve structure
                min_src_needed = len(src_ids) - pfx_pos[0]
                if keep_for_src < min_src_needed:
                    logger.debug("Cannot preserve FIM structure after truncation")
                    return None, None

                # Truncate from the beginning, but keep FIM structure
                start_idx = max(0, len(src_ids) - keep_for_src)
                if start_idx > pfx_pos[0]:
                    start_idx = pfx_pos[0]

                src_ids = src_ids[start_idx:]

                # Re-validate structure after truncation
                pfx_pos2 = self._find(src_ids, self.fim_prefix_id)
                sfx_pos2 = self._find(src_ids, self.fim_suffix_id)

                if not pfx_pos2 or not sfx_pos2 or not (pfx_pos2[0] < sfx_pos2[0] < len(src_ids) - 1):
                    return None, None

            # Assemble final sequences
            input_ids = src_ids + tgt_ids + tail
            labels = [IGNORE_INDEX] * len(src_ids) + tgt_ids

            if tail:
                labels += ([self.eos_id] if self.predict_eos else [IGNORE_INDEX])

            # Final validation
            if len(input_ids) != len(labels):
                logger.error(f"Length mismatch: input_ids={len(input_ids)}, labels={len(labels)}")
                return None, None

            return np.array(input_ids, dtype=np.int32), np.array(labels, dtype=np.int32)

        except Exception as e:
            logger.error(f"Error encoding sample: {e}")
            return None, None


def build_tokenizer(
    model_name_or_path: str,
    max_length: int,
    model_revision: str,
) -> Tuple[AutoTokenizer, int, int]:
    """
    Build tokenizer with FIM token support for Qwen models.
    Ensures <|fim_prefix|>, <|fim_middle|>, <|fim_suffix|> are properly registered.

    Returns (tokenizer, orig_vocab_size, new_vocab_size).
    """
    tok = AutoTokenizer.from_pretrained(
        model_name_or_path,
        revision=model_revision,
        trust_remote_code=True,
        use_fast=True,
    )

    orig_vocab_size = len(tok)

    # FIM markers
    fim_core = ("<|fim_prefix|>", "<|fim_middle|>", "<|fim_suffix|>")
    fim_optional = ("<|fim_pad|>",)

    def _as_added(ts):
        return [
            AddedToken(
                t,
                lstrip=False,
                rstrip=False,
                single_word=False,
                normalized=False,
                special=True,
            )
            for t in ts
        ]

    # Add FIM tokens as special tokens
    tok.add_tokens(_as_added(fim_core), special_tokens=True)
    tok.add_tokens(_as_added(fim_optional), special_tokens=True)
    tok.add_special_tokens({"additional_special_tokens": list(fim_core) + list(fim_optional)})

    # Sanity check: each FIM marker must be a single token
    for t in fim_core:
        ids = tok.encode(t, add_special_tokens=False)
        if len(ids) != 1:
            raise ValueError(f"{t} must be a single token after add_tokens; got {ids}")

    # Padding & max length
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token

    tok.model_max_length = max_length
    new_vocab_size = len(tok)

    return tok, orig_vocab_size, new_vocab_size


def iter_project_samples(file_paths: List[str]) -> Iterator[Tuple[str, str]]:
    """
    Iterate over CSV files containing (src, tgt) pairs.

    Expected CSV columns: src, tgt
    - src: prompt with FIM markers
    - tgt: complete oracle

    Yields: (src, tgt) tuples
    """
    for p in file_paths:
        logger.debug(f"Processing file: {p}")
        try:
            df = pd.read_csv(p, encoding="utf-8", engine="python", on_bad_lines="skip")
        except Exception as e:
            logger.error(f"Failed to read CSV {p}: {e}")
            continue

        # Normalize column names
        df.columns = df.columns.str.lower().str.strip()

        # Validate required columns
        required_cols = ['src', 'tgt']
        missing_cols = [col for col in required_cols if col not in df.columns]

        if missing_cols:
            logger.error(f"CSV {p} missing required columns: {missing_cols}")
            continue

        for idx, row in df.iterrows():
            try:
                src = str(row.get("src", "")).strip() if not pd.isna(row.get("src")) else ""
                tgt = str(row.get("tgt", "")).strip() if not pd.isna(row.get("tgt")) else ""

                # Only yield if both src and tgt are non-empty
                if src and tgt:
                    yield (src, tgt)
            except Exception as e:
                logger.warning(f"Skipping invalid row {idx} in {p}: {e}")
                continue


def sample_project_tokenized(
    files: List[str],
    target_tokens: int,
    encoder: FIMOracleEncoder,
    rng: random.Random,
    short_cut: int,
    med_cut: int,
    target_mix=(0.2, 0.4, 0.4),
    min_len: int = 300,
    per_bucket_buf: float = 0.10,
    overflow_keep_p: float = 0.25,
    early_stop_buf: float = 0.15,
) -> Tuple[List[np.ndarray], List[np.ndarray], int]:
    """
    Sample datapoints from a project according to token budget and length distribution.

    Returns: (input_ids_list, labels_list, total_tokens)
    """
    # Allocate token budget per bucket
    cap = [int(target_tokens * p) for p in target_mix]
    cap_plus = [int(c * (1 + per_bucket_buf)) for c in cap]

    # Tokens counter per bucket
    tok_sum = [0, 0, 0]
    soft_total = 0

    # Buckets of samples
    soft_buckets = [[], [], []]
    overflow = [[], [], []]

    # Iterate over samples
    for sample in iter_project_samples(files):
        # Encode the sample
        ids, lbl = encoder.encode(sample)

        # Discard empty samples
        if ids is None:
            continue

        # Count tokens
        L = int(len(ids))

        # Filter ultra-short samples
        if L < min_len:
            continue

        # Determine bucket
        b = bucket_from_len(L, short_cut, med_cut)

        # Fill buckets
        if tok_sum[b] <= cap_plus[b]:
            soft_buckets[b].append((ids, lbl, L))
            tok_sum[b] += L
            soft_total += L
        else:
            # Keep some overflow as backfill reservoir
            if rng.random() < overflow_keep_p:
                overflow[b].append((ids, lbl, L))

        # Early stop
        if soft_total >= int(target_tokens * (1 + early_stop_buf)):
            break

    # Shuffle buckets
    for k in range(len(soft_buckets)):
        rng.shuffle(soft_buckets[k])
        rng.shuffle(overflow[k])

    # Select samples
    sel_ids, sel_lbls, written = [], [], 0
    written_by_bucket = [0, 0, 0]
    leftovers = []

    for k in range(len(soft_buckets)):
        for _ids, _lbl, _L in soft_buckets[k]:
            if written >= target_tokens:
                break

            if written_by_bucket[k] <= cap_plus[k]:
                sel_ids.append(_ids)
                sel_lbls.append(_lbl)
                written += _L
                written_by_bucket[k] += _L
            else:
                leftovers.append((_ids, _lbl, _L))

        if written >= target_tokens:
            break

    # Fill remaining capacity with overflow
    if written < target_tokens:
        for k in range(len(soft_buckets)):
            leftovers.extend(overflow[k])

        rng.shuffle(leftovers)

        for _ids, _lbl, _L in leftovers:
            if written < target_tokens:
                sel_ids.append(_ids)
                sel_lbls.append(_lbl)
                written += _L
            if written >= target_tokens:
                break

    return sel_ids, sel_lbls, written


def read_split_csv(path: str) -> Dict[str, Dict]:
    """
    Read CSV with project metadata.

    Expected columns: project_id, num_oracles, size_bin

    Returns: dict mapping project_id to metadata
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


def find_project_files(data_dir: str, pids: List[str]) -> Dict[str, List[str]]:
    """
    Find CSV files for each project.

    Expected filename format: oracles-datapoints-{project_id}-*.csv
    """
    files = glob.glob(os.path.join(data_dir, "*.csv"))
    by = {pid: [] for pid in pids}

    for f in files:
        name = os.path.basename(f)
        # Extract project id from filename
        pid = name.replace("oracles-datapoints-", "").split("-")[0]

        if pid in by:
            by[pid].append(f)

    for k in by:
        by[k] = sorted(by[k])

    return by


def split_by_bins(meta: Dict[str, Dict]) -> Dict[str, List[str]]:
    """Split projects by size bin."""
    bins = {"Small": [], "Medium": [], "Large": []}

    for pid, m in meta.items():
        b = m["size_bin"]
        if b not in bins:
            b = "Medium"
        bins[b].append(pid)

    for k in bins:
        bins[k].sort()

    return bins


def allocate_bin_budgets(
    meta: Dict[str, Dict],
    total_token_budget: int,
    mode: str = "sqrt"
) -> Dict[str, int]:
    """
    Allocate token budget to each size bin.

    Modes:
    - proportional: by total oracles per bin
    - equal: equal tokens per bin
    - sqrt: by sqrt(total oracles per bin)
    """
    bins = split_by_bins(meta)
    sizes = {}

    for b, pids in bins.items():
        sizes[b] = sum(meta[pid]["num_oracles"] for pid in pids)

    if mode == "equal":
        w = {b: 1.0 for b in bins}
    elif mode == "proportional":
        w = {b: float(sizes[b]) for b in bins}
    else:  # sqrt
        w = {b: math.sqrt(max(1, sizes[b])) for b in bins}

    sw = sum(w.values()) or 1.0
    return {b: int(total_token_budget * (w[b] / sw)) for b in bins}


def allocate_project_budgets(
    meta: Dict[str, Dict],
    bin_tokens: Dict[str, int],
    alpha: float = 0.5,
    min_tokens_per_project: int = 8192
) -> Dict[str, int]:
    """
    Allocate tokens to projects within each bin.

    Uses weight = (num_oracles)^alpha:
    - alpha=0: uniform per project
    - alpha=1.0: proportional to num_oracles
    - alpha=0.5: √-scaling (robust default)
    """
    bins = split_by_bins(meta)
    budgets = {}

    for b, pids in bins.items():
        if not pids:
            continue

        weights = [(pid, max(1, meta[pid]["num_oracles"]) ** alpha) for pid in pids]
        sw = sum(w for _, w in weights) or 1.0

        for pid, w in weights:
            share = int(bin_tokens[b] * (w / sw))
            budgets[pid] = max(share, min_tokens_per_project)

    return budgets


def bucket_from_len(L: int, s_cut: int, m_cut: int) -> int:
    """Determine bucket index from length."""
    if L <= s_cut:
        return 0
    if L <= m_cut:
        return 1
    return 2


def build_split(
    split_name: str,
    pids_path: str,
    data_dir: str,
    out_dir: str,
    model_name_or_path: str,
    model_revision: str,
    max_length: int,
    total_token_budget: int,
    bin_allocation: str,
    alpha: float,
    drop_long: bool,
    samples_per_json_file: int,
    target_mix: Tuple[float, float, float],
    short_cut_frac: float,
    med_cut_frac: float,
    seed: int,
):
    """Build and save a tokenized dataset split."""
    # Initialize random generator
    rng = random.Random(seed)

    # Build tokenizer
    tok, tok_vocab_len_before, tok_vocab_len_after = build_tokenizer(
        model_name_or_path,
        max_length,
        model_revision
    )

    # Initialize encoder
    enc = FIMOracleEncoder(tok, max_len=max_length, drop_long=drop_long, append_eos=True)

    # Read project metadata
    meta = read_split_csv(pids_path)
    pids = sorted(meta.keys())
    files_by_pid = find_project_files(data_dir, pids)

    # Allocate token budgets
    bin_tokens = allocate_bin_budgets(meta, total_token_budget, mode=bin_allocation)
    proj_budget = allocate_project_budgets(
        meta, bin_tokens, alpha=alpha, min_tokens_per_project=max(max_length, 4096)
    )

    # Define length thresholds
    short_cut = int(max_length * short_cut_frac)
    med_cut = int(max_length * med_cut_frac)

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
        "drop_long": drop_long,
        "num_projects": len(pids),
        "projects": pids
    }

    # Save tokenizer and metadata
    tok_dir = os.path.join(out_dir, "tokenizer")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(tok_dir, exist_ok=True)

    tok.save_pretrained(tok_dir)

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

    for pid in pids:
        tokens_cap = proj_budget.get(pid, 0)
        files = files_by_pid.get(pid, [])

        if tokens_cap <= 0 or not files:
            continue

        # Sample and encode
        ids_list, lbl_list, got = sample_project_tokenized(
            files,
            tokens_cap,
            enc,
            rng,
            short_cut,
            med_cut,
            target_mix=target_mix
        )

        # Write samples
        for ids, lbl in zip(ids_list, lbl_list):
            writer.write(ids, lbl)

        total_written += got
        per_project[pid] = got

        logger.info(
            f"[{split_name}] {pid} ({meta[pid]['size_bin']}) -> "
            f"{got:,} tokens; total={total_written:,}"
        )

    # Close writer
    writer.close()

    # Save per-project stats
    with open(os.path.join(out_dir, f"{split_name}.per_project_tokens.json"), "w") as f:
        json.dump(per_project, f, indent=2)

    logger.info(f"[{split_name}] DONE. total_tokens={total_written:,}")


def parse_args():
    """Parse command line arguments."""
    ap = argparse.ArgumentParser()
    ap.add_argument("--model_name_or_path", required=True,
                    help="Path to pretrained model or model identifier")
    ap.add_argument("--model_revision", type=str, default=None,
                    help="HF revision (commit hash or tag)")
    ap.add_argument("--data_dir", required=True,
                    help="Path to data directory with CSV files")
    ap.add_argument("--train_csv", required=True,
                    help="Path to training CSV with project list")
    ap.add_argument("--val_csv", required=True,
                    help="Path to validation CSV with project list")
    ap.add_argument("--out_dir", required=True,
                    help="Path to output directory")
    ap.add_argument("--max_length", type=int, default=8192,
                    help="Maximum sequence length")
    ap.add_argument("--train_token_budget", type=int, default=2_000_000_000,
                    help="Token budget for training")
    ap.add_argument("--val_token_budget", type=int, default=40_000_000,
                    help="Token budget for validation")
    ap.add_argument("--bin_allocation", choices=["proportional", "equal", "sqrt"],
                    default="sqrt", help="Token allocation strategy")
    ap.add_argument("--alpha", type=float, default=0.5,
                    help="Per-project weighting exponent")
    ap.add_argument("--target_mix", type=str, default="0.4,0.4,0.2",
                    help="Short,Medium,Long token share (comma-separated)")
    ap.add_argument("--short_cut_frac", type=float, default=0.33,
                    help="Short ≤ frac*max_length")
    ap.add_argument("--med_cut_frac", type=float, default=0.66,
                    help="Medium ≤ frac*max_length")
    ap.add_argument("--samples_per_json_file", type=int, default=1000,
                    help="Samples per JSON file")
    ap.add_argument("--seed", type=int, default=42,
                    help="Random seed")
    ap.add_argument("--train_drop_long", action="store_true",
                    help="Drop long samples in training")
    ap.add_argument("--val_drop_long", action="store_true",
                    help="Drop long samples in validation")

    return ap.parse_args()


def resolve_snapshot(model_name_or_path: str, model_revision: Optional[str]):
    """Resolve model snapshot for reproducibility."""
    if os.path.isdir(model_name_or_path):
        resolved_path = os.path.abspath(model_name_or_path)
        used_revision = "local"
    else:
        resolved_path = snapshot_download(repo_id=model_name_or_path, revision=model_revision)
        used_revision = Path(resolved_path).name

    return resolved_path, used_revision


def main():
    """Main entry point."""
    try:
        args = parse_args()

        # Validate arguments
        mix = tuple(float(x) for x in args.target_mix.split(","))
        assert len(mix) == 3 and abs(sum(mix) - 1.0) < 1e-6, "target_mix must sum to 1.0"
        assert 0.0 < args.short_cut_frac < args.med_cut_frac < 1.0, "Invalid cut fractions"

        logger.info("Starting dataset tokenization process...")
        logger.info(f"Model: {args.model_name_or_path}")
        logger.info(f"Max length: {args.max_length}")

        resolved_path, used_revision = resolve_snapshot(args.model_name_or_path, args.model_revision)

        # Build train split
        try:
            build_split(
                "train", args.train_csv, args.data_dir, args.out_dir,
                resolved_path, used_revision, args.max_length,
                args.train_token_budget, args.bin_allocation, args.alpha,
                args.train_drop_long, args.samples_per_json_file, mix,
                args.short_cut_frac, args.med_cut_frac, args.seed
            )
        except Exception as e:
            logger.error(f"Failed to build train split: {e}")
            raise

        # Build val split
        try:
            build_split(
                "val", args.val_csv, args.data_dir, args.out_dir,
                resolved_path, used_revision, args.max_length,
                args.val_token_budget, args.bin_allocation, args.alpha,
                args.val_drop_long, max(10_000_000, args.samples_per_json_file // 5), mix,
                args.short_cut_frac, args.med_cut_frac, args.seed + 1
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
