#!/usr/bin/env python3

"""
Count exact matches between the ground-truth (tgt column) and the text found
between [oracle] … [/oracle] in the prediction (generated column) for every CSV file
under the *inference* directory.

A separate score is kept for each first-level sub-folder (e.g. qwen2.5_1.5b-1).

Uses Claude Sonnet to determine semantic equivalence between oracles.

Required packages: pandas, numpy, anthropic
(`pip install pandas numpy anthropic`).

Required environment variable: ANTHROPIC_API_KEY
"""

import os
from dotenv import load_dotenv
import re
import json
import sys
import pandas as pd
from collections import defaultdict
import anthropic

load_dotenv()
# Check for API key in environment variables
api_key = os.getenv("ANTHROPIC_API_KEY")
if not api_key:
    print("ERROR: ANTHROPIC_API_KEY environment variable not set.")
    print("Please set it with: export ANTHROPIC_API_KEY='your_api_key_here'")
    sys.exit(1)

# Initialize Anthropic client
try:
    client = anthropic.Anthropic(api_key=api_key)
except Exception as e:
    print(f"ERROR: Failed to initialize Anthropic client: {e}")
    sys.exit(1)


def create_claude_request(id: str, code1: str, code2: str) -> dict:
    """
    Create request to batch to compare two Java code snippets and determine if they are semantically equivalent.

    Args:
        id (str): The identifier of the request
        code1 (str): First Java code snippet (ground truth oracle).
        code2 (str): Second Java code snippet (generated oracle).

    Returns:
        dict: The information to generate a request
    """
    prompt = f"""You are a Java code analysis expert. Analyze the following two test assertion statements and determine if they are semantically equivalent.\n\nGround Truth Oracle:\n{code1}\n\nGenerated Oracle:\n{code2}\n\nTwo assertions are equivalent if they verify the same condition on the same object(s), even if written differently. Some assertions can have a parameter that is a message in case of error. If one of the two assertion does not have the message or the two messages are different the assertions are still considered equivalent.\nRespond with ONLY "yes" if they are equivalent, or "no" if they are not. Do not provide any explanation."""
    return {
        "custom_id": f"{id}",
        "params": {
            "model": "claude-sonnet-4-5-20250929",
            "max_tokens": 10,
            "messages": [
                {"role": "user", "content": prompt}
            ]
        }
    }


MODEL_NAME = "Qwen/Qwen2.5-Coder-3B"
ROOT = f"/Users/davidemolinelli/Downloads/tracto/rqs/output/rq1/tracto/{MODEL_NAME}/1/inference"
STATS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output", "rq1", "tracto", "Qwen", "Qwen2.5-Coder-3B","1", "stats")
BATCH_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "output", "rq1", "tracto", "Qwen", "Qwen2.5-Coder-3B","1", "batch")

stats = defaultdict(lambda: {
    "strict": 0,
    "flex": 0,
    "empty": 0,
    "total": 0,
    "inference_time": 0.0
})

# Check if ROOT directory exists
if not os.path.exists(ROOT):
    print(f"ERROR: Root directory not found: {ROOT}")
    sys.exit(1)

processed_files = 0
claude_batch_requests = []
request_counter = 0
ids_correct=[]

for dirpath, _, files in os.walk(ROOT):
    csvs = [f for f in files if f.lower().endswith(".csv")]
    if not csvs:
        continue

    model = MODEL_NAME

    for csv in csvs:
        csv_path = os.path.join(dirpath, csv)
        try:
            # Read CSV with header
            df = pd.read_csv(
                csv_path,
                dtype=str,
                keep_default_na=False
            )
        except FileNotFoundError:
            print(f"WARNING: File not found: {csv_path}")
            continue
        except pd.errors.EmptyDataError:
            print(f"WARNING: Empty CSV file: {csv_path}")
            continue
        except pd.errors.ParserError as e:
            print(f"WARNING: Failed to parse CSV {csv_path}: {e}")
            continue
        except Exception as e:
            print(f"WARNING: Unexpected error reading {csv_path}: {e}")
            continue

        # Verify CSV has required columns
        required_cols = ['test_id', 'tgt', 'generated', 'status', 'time']
        missing_cols = [col for col in required_cols if col not in df.columns]
        if missing_cols:
            print(f"WARNING: CSV {csv_path} missing columns: {missing_cols}. Skipping.")
            continue

        processed_files += 1

        # Extract columns by name
        test_ids = df['test_id'].astype(str).str.strip()
        gt = df['tgt'].astype(str).str.strip()
        pred_raw = df['generated'].astype(str)
        status = df['status'].astype(str).str.strip()

        # Extract inference times from 'time' column
        try:
            inference_times = pd.to_numeric(df['time'], errors='coerce').fillna(0.0)
        except Exception:
            inference_times = pd.Series([0.0] * len(df))

        for idx, (test_id, g, p, s, inf_time) in enumerate(zip(test_ids, gt, pred_raw, status, inference_times)):
            if s == "OK":
                ids_correct.append(("_split_".join(test_id.split("_split_")[:-1]),g))
                strict = (g.replace(" ", "") == p.replace(" ", "")) or (g.replace(" ", "") == (p.replace(" ", "") + ";"))
                flex = strict or (g.replace(" ", "") in p.replace(" ", "")) or (p.replace(" ", "") in g.replace(" ", ""))

                if not flex:
                    claude_batch_requests.append(create_claude_request(f"llm-r-{request_counter}", g, p))
                    request_counter += 1

                stats[model]["strict"] += int(strict)
                stats[model]["flex"] += int(flex)
                stats[model]["inference_time"] += float(inf_time)

                # Prediction is empty or doesn't contain 'assert'
                if p == "":
                    stats[model]["empty"] += 1

                stats[model]["total"] += 1
            else:
                stats[model]["empty"] += 1
                stats[model]["total"] += 1
                stats[model]["inference_time"] += float(inf_time or 0.0)


print(f"\n✓ Processed {processed_files} CSV file(s)")

# ------------------------- console output -------------------------
print("\n" + "=" * 80)
print("METRICS:")
print(" strict = (groundtruth == prediction) or (groundtruth == prediction + ';')")
print(" flex = strict or (groundtruth in prediction) or (prediction in groundtruth)")
print("=" * 80)
print("\n" + "=" * 80)
print("RESULTS:")
print("=" * 80 + "\n")

for m, s in sorted(stats.items()):
    t = s["total"]
    if t == 0:
        continue

    strict_pct = 100 * s["strict"] / t
    flex_pct = 100 * s["flex"] / t
    empty_pct = 100 * s["empty"] / t
    inference_time_avg = s["inference_time"] / t

    print(f"{m}")
    print(f" Strict: {s['strict']:4d} / {t} ({strict_pct:5.1f}%)")
    print(f" Flexible: {s['flex']:4d} / {t} ({flex_pct:5.1f}%)")
    print(f" Empty: {s['empty']:4d} / {t} ({empty_pct:5.1f}%)")
    print(f" Inf. Time: {inference_time_avg:.2f} sec")
    print()

# ------------------------ JSON serialisation ----------------------
json_data = {
    model: {
        "total": s["total"],
        "strict": s["strict"],
        "strict_percent": round(100 * s["strict"] / s["total"], 2) if s["total"] else 0,
        "flexible": s["flex"],
        "flexible_percent": round(100 * s["flex"] / s["total"], 2) if s["total"] else 0,
        "empty": s["empty"],
        "empty_percent": round(100 * s["empty"] / s["total"], 2) if s["total"] else 0,
        "inference_time_avg": round(s["inference_time"] / s["total"], 2) if s["total"] else 0,
    }
    for model, s in stats.items()
}

try:
    if not os.path.exists(STATS_DIR):
        os.makedirs(STATS_DIR)
    if not os.path.exists(BATCH_DIR):
        os.makedirs(BATCH_DIR)
    STATS_FILE = os.path.join(STATS_DIR, "inference_stats.json")
    BATCH_FILE = os.path.join(BATCH_DIR, "claude_batch.json")
    with open(STATS_FILE, "w", encoding="utf-8") as f:
        json.dump(json_data, f, indent=2, ensure_ascii=False)
    with open(BATCH_FILE, "w", encoding="utf-8") as f:
        json.dump(claude_batch_requests, f, ensure_ascii=False)
    print(f"✓ Stats written to: {STATS_FILE}")
    print(f"✓ Batch written to: {BATCH_FILE}")
    print(f"✓ Total Claude batch requests: {len(claude_batch_requests)}")
except Exception as e:
    print(f"ERROR: Failed to write stats file: {e}")
    sys.exit(1)

#print(ids_correct)