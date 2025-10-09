#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Balanced, pre-tokenized SFT corpus using existing size bins (Small/Medium/Large).

- Uses train/val CSVs with columns: project_id,num_oracles,size_bin
- Allocates token budgets per bin and per project (√-scaling)
- Encodes chat with Qwen tokenizer (+ <|im_start|>, <|im_end|>)
- Assistant-only supervision (IGNORE_INDEX=-100)
- Length buckets by *token length after encoding*: short/med/long
- Sharded memmap output: {split}.{shard}.{input_ids|labels}.bin, .idx, {split}.meta.json

Example:
  python build_tokenized_dataset_bins.py \
    --model_name Qwen/Qwen2.5-Coder-14B-Instruct \
    --data_dir   /path/jsonl \
    --train_csv  /path/splits/train_projects.csv \
    --val_csv    /path/splits/val_projects.csv \
    --out_dir    /path/tokenized \
    --train_token_budget 2000000000 \
    --val_token_budget   40000000 \
    --max_length 4096 \
    --bin_allocation sqrt \
    --alpha 0.5 \
    --target_mix 0.4,0.4,0.2 \
    --tokens_per_shard 50000000 \
    --train_drop_long
"""

import os, io, csv, glob, json, math, random, argparse
from typing import Dict, List, Tuple, Iterable
import numpy as np
import orjson
import transformers
from huggingface_hub import snapshot_download
from pathlib import Path

IGNORE_INDEX = -100

# ---------------- Tokenizer & encoding identical to training ---------------- #

def build_tokenizer(model_path: str, model_max_length: int):
    tok = transformers.AutoTokenizer.from_pretrained(
        model_path,                    # resolved snapshot dir or local model dir
        trust_remote_code=True,
        model_max_length=model_max_length,
        padding_side="right",
    )
    if tok.pad_token_id is None:
        tok.pad_token = tok.eos_token
    tok.add_special_tokens({"additional_special_tokens": ["<|im_start|>", "<|im_end|>"]})
    return tok

class QwenChatEncoder:
    def __init__(self, tok, max_length: int, only_last_turn_loss=True, drop_long=False):
        self.tok = tok
        self.maxlen = max_length
        self.only_last = only_last_turn_loss
        self.drop_long = drop_long
        self.pad_id = tok.pad_token_id
        self.im_start_id = tok("<|im_start|>").input_ids[0]
        self.im_end_id   = tok("<|im_end|>").input_ids[0]
        self.nl_id       = tok("\n", add_special_tokens=False).input_ids[-1:]
        self._sys = tok("system", add_special_tokens=False).input_ids + self.nl_id
        self._usr = tok("user",   add_special_tokens=False).input_ids + self.nl_id
        self._asst= tok("assistant", add_special_tokens=False).input_ids + self.nl_id

    def encode(self, msgs: List[Dict]) -> Tuple[np.ndarray, np.ndarray]:
        ids: List[int] = []
        lbl: List[int] = []

        # system
        sys_msg = "You are a testing developer assistant. You must help to automatize the process of generating test oracles, token-by-token."
        if msgs and msgs[0].get("role") == "system" and msgs[0].get("content"):
            sys_msg = msgs[0]["content"]
        sys_ids = [self.im_start_id] + self._sys + \
                  self.tok(sys_msg, add_special_tokens=False).input_ids + \
                  [self.im_end_id] + self.nl_id
        ids += sys_ids
        lbl += [self.im_start_id] + [IGNORE_INDEX]*(len(sys_ids)-3) + [self.im_end_id] + self.nl_id

        # turns
        turns = msgs[1:] if msgs and msgs[0].get("role") == "system" else msgs
        for j, s in enumerate(turns):
            role = s.get("role")
            if role not in ("user","assistant","system"):
                continue
            role_tok = {"user": self._usr, "assistant": self._asst, "system": self._sys}[role]
            content_ids = self.tok(s.get("content",""), add_special_tokens=False).input_ids
            full = [self.im_start_id] + role_tok + content_ids + [self.im_end_id] + self.nl_id
            ids += full
            if role == "user" or (self.only_last and j < len(turns)-1):
                tgt = [self.im_start_id] + [IGNORE_INDEX]*(len(full)-3) + [self.im_end_id] + self.nl_id
            elif role == "assistant":
                prefix_len = 1 + len(role_tok)
                tgt = [self.im_start_id] + [IGNORE_INDEX]*prefix_len + full[prefix_len+1:-2] + [self.im_end_id] + self.nl_id
            else:
                tgt = [self.im_start_id] + [IGNORE_INDEX]*(len(full)-3) + [self.im_end_id] + self.nl_id
            lbl += tgt

        if len(ids) > self.maxlen:
            if self.drop_long:
                return None, None
            ids = ids[:self.maxlen]
            lbl = lbl[:self.maxlen]

        return np.asarray(ids, np.int32), np.asarray(lbl, np.int32)

# ---------------- CSVs, binning, and budgets ---------------- #

def read_split_csv(path: str) -> Dict[str, Dict]:
    """Return {project_id: {'num_oracles': int, 'size_bin': 'Small'|...}}"""
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
    files = glob.glob(os.path.join(data_dir, "*.jsonl"))
    by = {pid: [] for pid in pids}
    for p in files:
        name = os.path.basename(p)
        pid = name.replace("oracles-datapoints-", "").split("-")[0]
        if pid in by:
            by[pid].append(p)
    for k in by:
        by[k] = sorted(by[k])
    return by

def split_by_bins(meta: Dict[str, Dict]) -> Dict[str, List[str]]:
    bins = {"Small": [], "Medium": [], "Large": []}
    for pid, m in meta.items():
        b = m["size_bin"]
        if b not in bins: b = "Medium"
        bins[b].append(pid)
    for k in bins: bins[k].sort()
    return bins

def allocate_bin_budgets(meta: Dict[str, Dict], total_token_budget: int, mode: str = "sqrt") -> Dict[str, int]:
    """
    mode ∈ {'proportional','equal','sqrt'}
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
    return {b: int(total_token_budget * (w[b]/sw)) for b in bins}

def allocate_project_budgets(meta: Dict[str, Dict], bin_tokens: Dict[str, int], alpha: float = 0.5,
                             min_tokens_per_project: int = 8192) -> Dict[str, int]:
    """
    Inside each bin, distribute its budget across projects using weight = (num_oracles)^alpha.
    alpha=0   -> uniform per project
    alpha=1.0 -> proportional to num_oracles
    alpha=0.5 -> √-scaling (robust default)
    """
    bins = split_by_bins(meta)
    budgets = {}
    for b, pids in bins.items():
        if not pids: continue
        weights = [(pid, max(1, meta[pid]["num_oracles"]) ** alpha) for pid in pids]
        sw = sum(w for _, w in weights) or 1.0
        for pid, w in weights:
            share = int(bin_tokens[b] * (w / sw))
            budgets[pid] = max(share, min_tokens_per_project)
    return budgets

# ---------------- Streaming, token-length buckets, sampling ---------------- #

def iter_project_msgs(files: List[str]) -> Iterable[List[Dict]]:
    for fp in files:
        with io.open(fp, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                try:
                    obj = orjson.loads(line)
                except Exception:
                    continue
                msgs = obj["messages"] if "messages" in obj else obj
                if isinstance(msgs, list) and msgs:
                    yield msgs

def bucket_from_len(L: int, s_cut: int, m_cut: int) -> int:
    # 0=short,1=med,2=long
    if L <= s_cut: return 0
    if L <= m_cut: return 1
    return 2

def sample_project_tokenized(files: List[str],
                             target_tokens: int,
                             encoder: QwenChatEncoder,
                             rng: random.Random,
                             short_cut: int,
                             med_cut: int,
                             target_mix=(0.4,0.4,0.2)) -> Tuple[List[np.ndarray], List[np.ndarray], int]:
    cap = [int(target_tokens*p) for p in target_mix]
    tok_sum = [0,0,0]
    total = 0
    buckets = [[],[],[]]

    for msgs in iter_project_msgs(files):
        ids, lbl = encoder.encode(msgs)
        if ids is None:
            continue
        L = int(len(ids))
        if L < 32:
            continue
        b = bucket_from_len(L, short_cut, med_cut)
        # prefer filling deficit buckets; occasionally accept anyway to add variety
        if tok_sum[b] < cap[b] or rng.random() < 0.1:
            buckets[b].append((ids,lbl,L))
            tok_sum[b] += L
            total += L
        if total >= int(target_tokens*1.15):
            break

    items = []
    for k in range(3):
        rng.shuffle(buckets[k]); items.extend(buckets[k])
    rng.shuffle(items)

    sel_ids, sel_lbls, written = [], [], 0
    for ids,lbl,L in items:
        if written + L > target_tokens:
            if written < target_tokens and L < 2048:
                sel_ids.append(ids); sel_lbls.append(lbl); written += L
            break
        sel_ids.append(ids); sel_lbls.append(lbl); written += L
    return sel_ids, sel_lbls, written

# ---------------- Sharded memmap writer ---------------- #

class ShardWriter:
    def __init__(self, out_dir: str, split: str, tokens_per_shard: int):
        os.makedirs(out_dir, exist_ok=True)
        self.out_dir = out_dir; self.split = split
        self.tokens_per_shard = tokens_per_shard
        self.shard_id = 0
        self._open_new()

    def _open_new(self):
        base = os.path.join(self.out_dir, f"{self.split}.{self.shard_id:05d}")
        self.ids_path = base + ".input_ids.bin"
        self.lbl_path = base + ".labels.bin"
        self.idx_path = base + ".idx"
        self.ids_f = open(self.ids_path, "wb")
        self.lbl_f = open(self.lbl_path, "wb")
        self.idx = [0]
        self.tok_count = 0
        self.n_samples = 0

    def _roll(self, add_tokens: int):
        if self.tok_count + add_tokens > self.tokens_per_shard and self.n_samples > 0:
            self.close()
            self.shard_id += 1
            self._open_new()

    def write(self, input_ids: np.ndarray, labels: np.ndarray):
        L = int(input_ids.shape[0])
        self._roll(L)
        self.ids_f.write(input_ids.tobytes()); self.lbl_f.write(labels.tobytes())
        self.tok_count += L; self.n_samples += 1; self.idx.append(self.tok_count)

    def close(self):
        self.ids_f.flush(); self.ids_f.close()
        self.lbl_f.flush(); self.lbl_f.close()
        with open(self.idx_path, "wb") as f:
            np.asarray(self.idx, np.uint64).tofile(f)

# ---------------- Build a split ---------------- #

def build_split(split_name: str,
                csv_path: str,
                data_dir: str,
                out_dir: str,
                model_name: str,
                model_revision: str,
                max_length: int,
                total_token_budget: int,
                bin_allocation: str,
                alpha: float,
                drop_long: bool,
                tokens_per_shard: int,
                target_mix: Tuple[float,float,float],
                short_cut_frac: float,
                med_cut_frac: float,
                seed: int):

    rng = random.Random(seed)
    tok = build_tokenizer(model_name, max_length)
    enc = QwenChatEncoder(tok, max_length=max_length, only_last_turn_loss=True, drop_long=drop_long)

    # CSV -> meta dict
    meta = read_split_csv(csv_path)
    pids = sorted(meta.keys())
    files_by_pid = find_project_files(data_dir, pids)

    # budgets (bin -> per-project)
    bin_tokens = allocate_bin_budgets(meta, total_token_budget, mode=bin_allocation)
    proj_budget = allocate_project_budgets(meta, bin_tokens, alpha=alpha, min_tokens_per_project=max(max_length, 4096))

    # length cuts in tokens (post-encoding)
    short_cut = int(max_length * short_cut_frac)   # e.g., 0.33 * 4096
    med_cut   = int(max_length * med_cut_frac)     # e.g., 0.66 * 4096

    # meta file
    meta_out = {
        "split": split_name,
        "model_name": model_name,
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
        "vocab_size": len(tok),
        "special_token_ids": {
            "<|im_start|>": tok("<|im_start|>").input_ids[0],
            "<|im_end|>": tok("<|im_end|>").input_ids[0],
        }
    }
    tok_dir = os.path.join(out_dir, "tokenizer")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(tok_dir, exist_ok=True)
    tok.save_pretrained(tok_dir)
    with open(os.path.join(out_dir, f"{split_name}.meta.json"), "w") as f:
        json.dump(meta_out, f, indent=2)
    with open(os.path.join(out_dir, "base_model.json"), "w") as bf:
        json.dump({"base_model_name_or_path": model_name,
                   "base_model_revision": model_revision or "main"}, bf, indent=2)

    writer = ShardWriter(out_dir, split_name, tokens_per_shard=tokens_per_shard)

    total_written = 0
    per_project = {}
    for pid in pids:
        tgt = proj_budget.get(pid, 0)
        files = files_by_pid.get(pid, [])
        if tgt <= 0 or not files:
            continue
        ids_list, lbl_list, got = sample_project_tokenized(
            files, tgt, enc, rng, short_cut, med_cut, target_mix=target_mix
        )
        for ids, lbl in zip(ids_list, lbl_list):
            writer.write(ids, lbl)
        total_written += got
        per_project[pid] = got
        print(f"[{split_name}] {pid} ({meta[pid]['size_bin']}) -> {got:,} tokens; total={total_written:,}")

    writer.close()
    with open(os.path.join(out_dir, f"{split_name}.per_project_tokens.json"), "w") as f:
        json.dump(per_project, f, indent=2)
    print(f"[{split_name}] DONE. total_tokens={total_written:,}")

# ---------------- CLI ---------------- #

def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model_name", required=True)
    ap.add_argument("--model_revision", type=str, default=None, help="HF revision (commit hash or tag) to pin the base tokenizer.")
    ap.add_argument("--data_dir", required=True)
    ap.add_argument("--train_csv", required=True)
    ap.add_argument("--val_csv", required=True)
    ap.add_argument("--out_dir", required=True)
    ap.add_argument("--max_length", type=int, default=4096)
    ap.add_argument("--train_token_budget", type=int, default=2_000_000_000)
    ap.add_argument("--val_token_budget",   type=int, default=40_000_000)
    ap.add_argument("--bin_allocation", choices=["proportional","equal","sqrt"], default="sqrt",
                    help="How to allocate tokens across Small/Medium/Large bins.")
    ap.add_argument("--alpha", type=float, default=0.5, help="Within-bin per-project weighting exponent on num_oracles.")
    ap.add_argument("--target_mix", type=str, default="0.4,0.4,0.2",
                    help="Short,Medium,Long token share per project (comma).")
    ap.add_argument("--short_cut_frac", type=float, default=0.33, help="Short ≤ frac*max_length.")
    ap.add_argument("--med_cut_frac",   type=float, default=0.66, help="Medium ≤ frac*max_length.")
    ap.add_argument("--tokens_per_shard", type=int, default=50_000_000)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--train_drop_long", action="store_true")
    ap.add_argument("--val_drop_long", action="store_true")
    return ap.parse_args()

def main():
    args = parse_args()
    mix = tuple(float(x) for x in args.target_mix.split(","))
    assert len(mix) == 3 and abs(sum(mix) - 1.0) < 1e-6, "--target_mix must have 3 values summing to 1.0"

    # Resolve snapshot once (commit SHA) for reproducibility
    if os.path.isdir(args.model_name):
        resolved_path = os.path.abspath(args.model_name)
        used_revision = "local"
    else:
        resolved_path = snapshot_download(repo_id=args.model_name, revision=args.model_revision)
        used_revision = Path(resolved_path).name  # commit

    build_split(
        "train", args.train_csv, args.data_dir, args.out_dir,
        resolved_path, used_revision,  # <-- note: path, then commit
        args.max_length, args.train_token_budget, args.bin_allocation, args.alpha,
        args.train_drop_long, args.tokens_per_shard, mix, args.short_cut_frac,
        args.med_cut_frac, args.seed
    )
    build_split(
        "val", args.val_csv, args.data_dir, args.out_dir,
        resolved_path, used_revision,  # <-- same here
        args.max_length, args.val_token_budget, args.bin_allocation, args.alpha,
        args.val_drop_long, max(10_000_000, args.tokens_per_shard // 5), mix,
        args.short_cut_frac, args.med_cut_frac, args.seed + 1
    )

if __name__ == "__main__":
    main()
