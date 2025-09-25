import argparse
import csv
import json
import math
import os
import sys
import pandas as pd
from statistics import median, pstdev
from transformers import AutoTokenizer

def parse_bin_edges(s):
    # Accept comma-separated integers or 'inf'
    edges = []
    for tok in s.split(","):
        tok = tok.strip().lower()
        if tok in ("inf", "+inf", "infinity"):
            edges.append(float("inf"))
        else:
            try:
                v = int(tok)
                if v < 0:
                    raise ValueError
                edges.append(v)
            except Exception:
                raise ValueError(f"Invalid bin edge: {tok!r}")
    if len(edges) < 2:
        raise ValueError("At least two bin edges required.")
    if any(edges[i] >= edges[i + 1] for i in range(len(edges) - 1)):
        raise ValueError("Bin edges must be strictly increasing.")
    return edges

def default_pow2_edges(max_val):
    # Start at 0, then 32, 64, 128, ... up to cover max_val, plus inf
    edges = [0, 32]
    while edges[-1] < max(32, max_val):
        edges.append(edges[-1] * 2)
    if edges[-1] < max_val:
        edges.append(max_val)
    edges.append(float("inf"))
    # Ensure strictly increasing
    dedup = []
    for e in edges:
        if not dedup or e > dedup[-1]:
            dedup.append(e)
    return dedup

def histogram(counts, edges):
    # counts each bin as [edges[i], edges[i+1]) except final bin includes upper
    bins = [0] * (len(edges) - 1)
    last_idx = len(bins) - 1
    j = 0
    for val in counts:
        # advance j to correct bin (works if edges small; for large edges use binary search)
        # We'll just binary search for robustness:
        lo, hi = 0, last_idx
        idx = last_idx
        while lo <= hi:
            mid = (lo + hi) // 2
            if edges[mid] <= val < edges[mid + 1] or (mid == last_idx and val >= edges[mid]):
                idx = mid
                break
            if val < edges[mid]:
                hi = mid - 1
            else:
                lo = mid + 1
        bins[idx] += 1
    return bins

def percentile_from_sorted(sorted_vals, q):
    # q as fraction in [0,1], inclusive
    if not sorted_vals:
        return None
    if q <= 0:
        return sorted_vals[0]
    if q >= 1:
        return sorted_vals[-1]
    k = (len(sorted_vals) - 1) * q
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_vals[f]
    d0 = sorted_vals[f] * (c - k)
    d1 = sorted_vals[c] * (k - f)
    return d0 + d1

def count_lengths(tokenizer, texts, include_special=False):
    enc = tokenizer(
        texts,
        add_special_tokens=include_special,
        return_attention_mask=False,
        return_token_type_ids=False,
        truncation=False,
        padding=False,
    )
    return [len(ids) for ids in enc["input_ids"]]

def main():
    parser = argparse.ArgumentParser(description="Compute token stats (src+tgt) per row using Qwen2.5-Coder tokenizer.")
    parser.add_argument("input_path", help="Path to input CSV with columns: src,tgt")
    parser.add_argument("output_path", help="Path to output path where to save the statistics.")
    parser.add_argument("--model", default="Qwen/Qwen2.5-Coder-14B-Instruct", help="Tokenizer model id (HF Hub).")
    parser.add_argument("--batch_size", type=int, default=512, help="Batch size for tokenization.")
    parser.add_argument("--delimiter", default=",", help="CSV delimiter (default: ,).")
    parser.add_argument("--encoding", default="utf-8", help="File encoding (default: utf-8).")
    parser.add_argument("--bin_edges", default=None, help="Comma-separated bin edges for histogram. Example: '0,32,64,128,256,512,1024,2048,4096,8192,inf'. If not provided, uses powers-of-two bins.")
    parser.add_argument("--quantiles", default="50,75,90,95,99", help="Comma-separated percentiles to report (0-100). Example: '50,75,90,95,99'. Set to empty string to skip.")
    parser.add_argument("--include-special", default=True, action="store_true", help="Include special tokens (BOS/EOS/etc.) in counts.")
    args = parser.parse_args()
    # Overall stats
    all_counts = []
    all_total_rows = 0

    if not os.path.exists(args.output_path):
        os.makedirs(args.output_path)

    # Collect files to process
    if os.path.isdir(args.input_path):
        prompt_dataset_files = []
        for root, dirs, files in os.walk(args.input_path):
            for file in files:
                if file.endswith('.csv'):
                    prompt_dataset_files.append(os.path.join(root, file))
    else:
        prompt_dataset_files = [args.input_path]

    try:
        tokenizer = AutoTokenizer.from_pretrained(
            args.model, use_fast=True, trust_remote_code=True
        )
    except Exception as e:
        print(f"Failed to load tokenizer '{args.model}': {e}", file=sys.stderr)
        sys.exit(1)

    # Process each csv file in the original dataset of prompts
    for file_path in prompt_dataset_files:
        print(f"Processing file: {file_path}")
        stats ={}
        project_id = os.path.basename(file_path).replace('oracles-datapoints-', '').split('-')[0]

        counts = []
        total_rows = 0

        try:
            # Define the chunk size
            chunksize = 1024
            # Read the CSV file in chunks
            for chunk in pd.read_csv(os.path.join(file_path), usecols=['src', 'tgt'], chunksize=chunksize):
                def flush():
                    nonlocal counts
                    if not buffer_src:
                        return
                    src_counts = count_lengths(tokenizer, buffer_src, include_special=args.include_special)
                    tgt_counts = count_lengths(tokenizer, buffer_tgt, include_special=args.include_special)
                    counts.extend(a + b for a, b in zip(src_counts, tgt_counts))
                    buffer_src.clear()
                    buffer_tgt.clear()
                buffer_src = []
                buffer_tgt = []
                # Process each row in the chunk
                for index, row in chunk.iterrows():
                    s = row.get("src") or ""
                    t = row.get("tgt") or ""
                    buffer_src.append(str(s))
                    buffer_tgt.append(str(t))
                    total_rows += 1
                    if len(buffer_src) >= args.batch_size:
                        flush()
                flush()
        except FileNotFoundError:
            print(f"File not found: {file_path}", file=sys.stderr)
            sys.exit(3)
        except UnicodeDecodeError as e:
            print(f"Encoding error while reading file: {e}", file=sys.stderr)
            sys.exit(4)

        if total_rows == 0 or not counts:
            print("No data rows found.")
            sys.exit(0)

        project_stats = compute_stats(counts, total_rows, args.bin_edges, args.quantiles)
        all_counts.extend(counts)
        all_total_rows += total_rows

        with open(os.path.join(args.output_path,f"prompt-stats-{project_id}.json"), "w", encoding="utf-8") as f:
            json.dump(project_stats, f)
    all_stats = compute_stats(all_counts, all_total_rows, args.bin_edges, args.quantiles)
    with open(os.path.join(args.output_path, f"prompt-stats-overall.json"), "w", encoding="utf-8") as f:
        json.dump(all_stats, f)

def compute_stats(counts, total_rows, quantiles, bin_edges):
    stats = {}
    total_tokens = sum(counts)
    # Core stats
    total_tokens = sum(counts)
    avg = total_tokens / total_rows
    min_tokens = min(counts)
    max_tokens = max(counts)
    med = median(counts)
    # Population std dev (all rows are the population)
    std = pstdev(counts)

    # Output
    print(f"Rows: {total_rows}")
    print(f"Total tokens (src+tgt): {total_tokens}")
    print(f"Average tokens per row (src+tgt): {avg:.2f}")
    print(f"Min tokens per row: {min_tokens}")
    print(f"Max tokens per row: {max_tokens}")
    print(f"Median tokens per row: {med}")
    print(f"Population std dev: {std:.6f}")

    stats["rows"] = total_rows
    stats["tokens"] = total_tokens
    stats["avg_tokens_per_row"] = round(avg, 2)
    stats["min_tokens_per_row"] = min_tokens
    stats["max_tokens_per_row"] = max_tokens
    stats["med_tokens_per_row"] = round(med, 2)
    stats["std_tokens_per_row"] = round(std, 2)

    # Quantiles
    q_values = []
    if quantiles is not None and quantiles.strip() != "":
        try:
            q_list = [float(x.strip()) for x in quantiles.split(",") if x.strip() != ""]
            if any(not (0 <= q <= 100) for q in q_list):
                raise ValueError
        except Exception:
            print(f"Invalid --quantiles: {quantiles!r}. Use comma-separated values in [0,100].", file=sys.stderr)
            sys.exit(5)
        sorted_counts = sorted(counts)
        for q in q_list:
            val = percentile_from_sorted(sorted_counts, q / 100.0)
            q_values.append((q, val))
    else:
        q_values = []

    # Histogram
    if bin_edges:
        try:
            edges = parse_bin_edges(bin_edges)
            stats["bin_edges"] = edges
        except ValueError as e:
            print(f"Invalid --bin_edges: {e}", file=sys.stderr)
            sys.exit(6)
    else:
        edges = default_pow2_edges(max_tokens)
    bin_counts = histogram(counts, edges)

    if q_values:
        print("Percentiles:")
        for q, v in q_values:
            print(f"  p{int(q) if q.is_integer() else q}: {v:.6f}")
        stats["q_values"] = [(q, v) for q, v in q_values]

    print("Histogram (bins are [left, right) except the last which is [left, +inf)):")
    total = len(counts)
    for i, c in enumerate(bin_counts):
        left = edges[i]
        right = edges[i + 1]
        if math.isinf(right):
            label = f"[{left}, +inf)"
        else:
            label = f"[{left}, {right})"
        pct = (c / total) * 100 if total else 0.0
        print(f"  {label:>14}: {c:>8} ({pct:6.2f}%)")

    return stats


if __name__ == "__main__":
    main()