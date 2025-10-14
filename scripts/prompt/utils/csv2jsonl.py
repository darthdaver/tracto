#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
csv2jsonl.py

Convert one or more CSV prompt files into ChatML-style JSONL suitable for SFT.

Expected CSV columns:
  - src : the full prompt context (everything that goes into <SRC>)
  - tgt : the target token to produce (e.g., IDENTIFIER:assertEquals, LPAREN:(, ...).
           If missing/NaN, the example will be treated as inference-only (no assistant message).

Usage:
  python csv2jsonl.py INPUT_DIR OUTPUT_DIR QUERY_TEMPLATE_TXT [--src-col src] [--tgt-col tgt]

Where:
  * INPUT_DIR contains one or more CSVs (e.g., train.csv, val.csv). Every .csv file found will be converted
    into a JSONL with the same base name in OUTPUT_DIR (e.g., train.jsonl, val.jsonl).
  * QUERY_TEMPLATE_TXT is a text file. The contents must include the placeholder "<SRC>",
    which will be replaced by the row['src'] value.

Each JSONL line has the schema:
  {
    "messages": [
      {"role": "user", "content": "<filled_prompt>"},
      {"role": "assistant", "content": "[token]...[/token]"}
    ],
    "format": "chatml"
  }
"""

import os
import sys
import json
import math
import argparse
from glob import glob
import pandas as pd


def preprocess_query(query_template: str, src_value: str) -> str:
    return query_template.replace("<SRC>", src_value)


def convert_one_csv(in_csv: str, out_jsonl: str, query_template: str, src_col: str, tgt_col: str) -> int:
    os.makedirs(os.path.dirname(out_jsonl), exist_ok=True)

    # Robust CSV load
    df = pd.read_csv(in_csv, engine="python", on_bad_lines="skip")

    if src_col not in df.columns:
        raise ValueError(f"Column '{src_col}' not found in {in_csv}. Available: {list(df.columns)}")

    if tgt_col not in df.columns:
        # allow missing target column, we will treat all rows as inference-only
        df[tgt_col] = None

    n_written = 0
    with open(out_jsonl, "w", encoding="utf-8") as f_out:
        for idx, row in df.iterrows():
            src_text = str(row[src_col])
            query = preprocess_query(query_template, src_text)

            tgt = row[tgt_col]
            if isinstance(tgt, float) and math.isnan(tgt):
                tgt = None
            if tgt is not None:
                tgt = str(tgt)

            messages = [{"role": "user", "content": query}]
            if tgt:
                messages.append({"role": "assistant", "content": "[token]" + tgt + "[/token]"})

            rec = {"messages": messages, "format": "chatml"}
            f_out.write(json.dumps(rec, ensure_ascii=False) + "\n")
            n_written += 1

    return n_written


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input_dir", help="Directory containing *.csv files (e.g., train.csv, val.csv).")
    ap.add_argument("output_dir", help="Directory where *.jsonl will be written.")
    ap.add_argument("query_template_path", help="Text file containing the prompt template with <SRC> placeholder.")
    ap.add_argument("--src-col", default="src", help="Name of the source/prompt column in CSV.")
    ap.add_argument("--tgt-col", default="tgt", help="Name of the target/answer column in CSV.")
    args = ap.parse_args()

    with open(args.query_template_path, "r", encoding="utf-8") as fh:
        query_template = fh.read()

    os.makedirs(args.output_dir, exist_ok=True)
    csv_files = sorted(glob(os.path.join(args.input_dir, "*.csv")))
    if not csv_files:
        raise SystemExit(f"No CSVs found in {args.input_dir}")

    print(f"[csv2jsonl] Found {len(csv_files)} CSV(s) in {args.input_dir}")
    for csv_path in csv_files:
        base = os.path.splitext(os.path.basename(csv_path))[0]
        out_path = os.path.join(args.output_dir, base + ".jsonl")
        n = convert_one_csv(csv_path, out_path, query_template, args.src_col, args.tgt_col)
        print(f"[csv2jsonl] Wrote {n} records to {out_path}")


if __name__ == "__main__":
    main()
