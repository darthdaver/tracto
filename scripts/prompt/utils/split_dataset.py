import argparse
import json
import math
import os
import random
import sys
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Optional, Tuple
import pandas as pd
import csv


# Set CSV field size limit to maximum at module level
def set_max_csv_field_size():
    """Set CSV field size limit to maximum possible value"""
    max_int = sys.maxsize
    while True:
        try:
            csv.field_size_limit(max_int)
            break
        except OverflowError:
            max_int = int(max_int / 10)


# Call this at module initialization
set_max_csv_field_size()


def parse_args():
    p = argparse.ArgumentParser(
        description="Split projects (one file per project) into train/val/test with size stratification and time-aware test."
    )
    p.add_argument("--data-dir", required=True,
                   help="Folder containing project files (.jsonl or .csv), one per project.")
    p.add_argument("--out-dir", required=True, help="Output directory for split files and summaries.")
    p.add_argument("--timestamp-field", default=None,
                   help="Field/column name holding per-oracle timestamp (ISO8601 or unix seconds/ms). If absent, fallback to row order.")
    p.add_argument("--test-tail-frac", type=float, default=0.2,
                   help="Fraction of most recent oracles to evaluate on within each test project (default 0.2).")
    p.add_argument("--min-test-per-project", type=int, default=30,
                   help="Minimum number of oracles to include in each test project (default 30).")
    p.add_argument("--min-oracles-for-test", type=int, default=50,
                   help="Projects with fewer oracles than this are excluded from test and sent to train/val (default 50).")
    p.add_argument("--seed", type=int, default=42, help="Random seed for reproducible splits.")
    # Split control: either ratios or absolute counts
    p.add_argument("--train-ratio", type=float, default=0.59,
                   help="Approx ratio for train projects (ignored if counts provided).")
    p.add_argument("--val-ratio", type=float, default=0.15,
                   help="Approx ratio for val projects (ignored if counts provided).")
    p.add_argument("--test-ratio", type=float, default=0.26,
                   help="Approx ratio for test projects (ignored if counts provided).")
    p.add_argument("--train-count", type=int, default=None, help="Absolute number of projects for train (optional).")
    p.add_argument("--val-count", type=int, default=None, help="Absolute number of projects for val (optional).")
    p.add_argument("--test-count", type=int, default=None, help="Absolute number of projects for test (optional).")
    p.add_argument("--materialize-test", action="store_true",
                   help="If set, write per-project test subset files into out_dir/test_subsets/.")
    return p.parse_args()


def is_supported_file(path: Path) -> bool:
    return path.suffix.lower() in (".jsonl", ".csv")


def project_id_from_path(path: Path) -> str:
    return path.stem.replace("oracles-datapoints-", "").split("-")[0]


def safe_mkdir(p: Path):
    p.mkdir(parents=True, exist_ok=True)


def parse_timestamp(val) -> Optional[float]:
    """Returns epoch seconds (float) or None"""
    if val is None or pd.isna(val):
        return None
    if isinstance(val, (int, float)):
        # Heuristic: treat > 10^12 as ms
        v = float(val)
        if v > 1e12:
            return v / 1000.0
        if v > 1e10:
            return v / 1000.0
        return v
    if isinstance(val, str):
        s = val.strip()
        if not s:
            return None
        # Try integer/float first
        try:
            v = float(s)
            if v > 1e12:
                return v / 1000.0
            if v > 1e10:
                return v / 1000.0
            return v
        except ValueError:
            pass
        # Try ISO 8601
        try:
            # Basic normalization: replace Z with +00:00 for fromisoformat
            s2 = s.replace("Z", "+00:00")
            dt = datetime.fromisoformat(s2)
            return dt.timestamp()
        except Exception:
            return None
    return None


def read_counts(data_dir: Path) -> List[Dict]:
    """Return list of dicts with keys: project_id, path, ext, num_oracles."""
    out = []
    for f in sorted(data_dir.iterdir()):
        if not f.is_file() or not is_supported_file(f):
            continue
        ext = f.suffix.lower()
        pid = project_id_from_path(f)
        n = 0
        try:
            if ext == ".jsonl":
                # Count lines for JSONL
                with f.open("r", encoding="utf-8") as fh:
                    for _ in fh:
                        n += 1
            else:  # csv
                # Use pandas with proper error handling
                df = pd.read_csv(f, encoding="utf-8", engine='python', on_bad_lines='warn')
                n = len(df)
        except Exception as e:
            print(f"Warning: failed to count rows in {f}: {e}", file=sys.stderr)
            n = 0
        out.append({"project_id": pid, "ext": ext, "num_oracles": n, "path": f})

    # Aggregate by project_id (in case multiple files per project)
    res = {}
    for row in out:
        if row["project_id"] not in res:
            res[row["project_id"]] = row
            res[row["project_id"]]["num_files"] = 1
        else:
            res[row["project_id"]]["num_oracles"] += row["num_oracles"]
            res[row["project_id"]]["num_files"] += 1
    return list(res.values())


def tertile_bins(projects: List[Dict]) -> Tuple[int, int]:
    """Return threshold values for tertiles and assign each project a bin label: Small, Medium, Large."""
    sizes = sorted(p["num_oracles"] for p in projects)
    if not sizes:
        return 0, 0
    n = len(sizes)
    # indices for 33rd and 66th percentiles (floor)
    i1 = max(0, min(n - 1, int(n * (1 / 3))))
    i2 = max(0, min(n - 1, int(n * (2 / 3))))
    t1 = sizes[i1]
    t2 = sizes[i2]

    # Assign bins
    for p in projects:
        sz = p["num_oracles"]
        if sz <= t1:
            p["size_bin"] = "Small"
        elif sz <= t2:
            p["size_bin"] = "Medium"
        else:
            p["size_bin"] = "Large"
    return t1, t2


def assign_splits(
        projects: List[Dict],
        train_count: Optional[int],
        val_count: Optional[int],
        test_count: Optional[int],
        train_ratio: float,
        val_ratio: float,
        test_ratio: float,
        min_oracles_for_test: int,
        seed: int
) -> Dict[str, List[Dict]]:
    random.seed(seed)

    # Separate by bin
    bins = {"Small": [], "Medium": [], "Large": []}
    for p in projects:
        bins[p["size_bin"]].append(p)

    # Shuffle within bins
    for b in bins.values():
        random.shuffle(b)

    N = len(projects)
    if all(v is not None for v in (train_count, val_count, test_count)):
        tr_n, va_n, te_n = train_count, val_count, test_count
        if tr_n + va_n + te_n != N:
            raise ValueError(f"Provided counts {tr_n}+{va_n}+{te_n} != total {N}")
    else:
        # ratios
        total_ratio = train_ratio + val_ratio + test_ratio
        if total_ratio <= 0:
            raise ValueError("Invalid ratios")
        train_ratio /= total_ratio
        val_ratio /= total_ratio
        test_ratio /= total_ratio
        tr_n = round(N * train_ratio)
        va_n = round(N * val_ratio)
        te_n = N - tr_n - va_n

    # First pick TEST from projects with >= min_oracles_for_test
    test_pool_bins = {k: [p for p in v if p["num_oracles"] >= min_oracles_for_test] for k, v in bins.items()}

    # Desired per bin proportional to bin size
    test_selected = []
    remaining_needed = te_n

    # First pass: proportional allocation
    for bin_name in ["Small", "Medium", "Large"]:
        bin_total = len(bins[bin_name])
        if bin_total == 0:
            continue
        desired_bin_test = round(te_n * (bin_total / N))
        candidates = test_pool_bins[bin_name]
        take = min(desired_bin_test, len(candidates))
        test_selected.extend(candidates[:take])
        test_pool_bins[bin_name] = candidates[take:]
        remaining_needed -= take

    # Second pass: fill shortages from any remaining candidates
    if remaining_needed > 0:
        leftovers = sum(test_pool_bins.values(), [])
        take = min(remaining_needed, len(leftovers))
        test_selected.extend(leftovers[:take])
        remaining_needed -= take

    if remaining_needed > 0:
        print(f"Warning: Not enough eligible test projects (need {te_n}, got {te_n - remaining_needed}). "
              f"Consider lowering --min-oracles-for-test.", file=sys.stderr)
        te_n = te_n - remaining_needed  # update actual count

    # Remove test from bins
    test_ids = set(p["project_id"] for p in test_selected)
    for bin_name in bins:
        bins[bin_name] = [p for p in bins[bin_name] if p["project_id"] not in test_ids]

    # Next pick VAL from remaining, proportionally
    val_selected = []
    remaining_val = va_n
    for bin_name in ["Small", "Medium", "Large"]:
        bin_total = len(bins[bin_name])
        if bin_total == 0:
            continue
        desired_bin_val = round(va_n * (bin_total / (N - len(test_selected))))
        take = min(desired_bin_val, bin_total)
        val_selected.extend(bins[bin_name][:take])
        bins[bin_name] = bins[bin_name][take:]
        remaining_val -= take

    # Fill shortages
    if remaining_val > 0:
        leftovers = sum(bins.values(), [])
        take = min(remaining_val, len(leftovers))
        val_selected.extend(leftovers[:take])
        # update bins
        leftover_ids = set(p["project_id"] for p in val_selected)
        for bin_name in bins:
            bins[bin_name] = [p for p in bins[bin_name] if p["project_id"] not in leftover_ids]

    # Remainder -> train
    train_selected = sum(bins.values(), [])

    return {"train": train_selected, "val": val_selected, "test": test_selected}


def select_time_aware_indices(
        path: Path, ext: str,
        timestamp_field: Optional[str],
        tail_frac: float,
        min_k: int
) -> List[int]:
    """
    Return sorted list of 0-based row indices selected for test (last tail_frac by timestamp; fallback to last-k).
    """
    records: List[Tuple[int, Optional[float]]] = []
    n_rows = 0

    if ext == ".jsonl":
        with path.open("r", encoding="utf-8") as fh:
            for i, line in enumerate(fh):
                n_rows += 1
                ts = None
                if timestamp_field:
                    try:
                        obj = json.loads(line)
                        ts = parse_timestamp(obj.get(timestamp_field))
                    except Exception:
                        ts = None
                records.append((i, ts))
    else:  # csv
        try:
            df = pd.read_csv(path, encoding="utf-8", engine='python', on_bad_lines='warn')
            n_rows = len(df)
            for i in range(n_rows):
                ts = None
                if timestamp_field and timestamp_field in df.columns:
                    ts = parse_timestamp(df.iloc[i][timestamp_field])
                records.append((i, ts))
        except Exception as e:
            print(f"Warning: failed to read {path} for time-aware selection: {e}", file=sys.stderr)
            return []

    # Determine k
    k = max(min_k, math.ceil(n_rows * tail_frac))
    k = min(k, n_rows)
    if k <= 0:
        return []

    # Prefer rows with timestamps; if many missing, use index order as tiebreaker
    def sort_key(item):
        idx, ts = item
        return (0 if ts is None else 1, ts if ts is not None else -float("inf"), idx)

    records_sorted = sorted(records, key=sort_key)

    # Take the last k (most recent)
    chosen = [idx for idx, _ in records_sorted[-k:]]
    return sorted(chosen)


def write_lists(out_dir: Path, splits: Dict[str, List[dict]]):
    for name in ["train", "val", "test"]:
        out_path = out_dir / f"{name}_projects.csv"
        data = []
        for p in splits[name]:
            data.append({"project_id": p["project_id"], "num_oracles": p["num_oracles"], "size_bin": p["size_bin"]})
        df = pd.DataFrame(data)
        df.to_csv(out_path, index=False, encoding="utf-8")


def write_summary(out_dir: Path, all_projects: List[Dict], splits: Dict[str, List[Dict]]):
    summary = {
        "total_projects": len(all_projects),
        "by_split_counts": {k: len(v) for k, v in splits.items()},
        "size_bins": {
            "Small": sum(1 for p in all_projects if p["size_bin"] == "Small"),
            "Medium": sum(1 for p in all_projects if p["size_bin"] == "Medium"),
            "Large": sum(1 for p in all_projects if p["size_bin"] == "Large"),
        },
        "splits": {
            k: [{"project_id": p["project_id"], "num_oracles": p["num_oracles"],
                 "num_files": p.get("num_files", 1), "size_bin": p["size_bin"]}
                for p in lst]
            for k, lst in splits.items()
        }
    }
    with (out_dir / "splits_summary.json").open("w", encoding="utf-8") as fh:
        json.dump(summary, fh, indent=2)


def materialize_test_subset(src_path: Path, dest_path: Path, indices: List[int], ext: str):
    """Write subset of rows based on indices"""
    if ext == ".jsonl":
        with src_path.open("r", encoding="utf-8") as fin, dest_path.open("w", encoding="utf-8") as fout:
            for i, line in enumerate(fin):
                if i in indices:
                    fout.write(line)
    else:  # csv
        try:
            df = pd.read_csv(src_path, encoding="utf-8", engine='python', on_bad_lines='warn')
            selected_df = df.iloc[indices]
            selected_df.to_csv(dest_path, index=False, encoding="utf-8")
        except Exception as e:
            print(f"Warning: failed to materialize test subset for {src_path}: {e}", file=sys.stderr)


def main():
    args = parse_args()
    data_dir = Path(args.data_dir)
    out_dir = Path(args.out_dir)
    safe_mkdir(out_dir)
    safe_mkdir(out_dir / "test_selection")

    # 1) Scan and count
    projects = read_counts(data_dir)
    if not projects:
        print("No .jsonl or .csv files found.", file=sys.stderr)
        sys.exit(1)

    # 2) Bin by size
    t1, t2 = tertile_bins(projects)

    # 3) Assign splits
    splits = assign_splits(
        projects,
        train_count=args.train_count,
        val_count=args.val_count,
        test_count=args.test_count,
        train_ratio=args.train_ratio,
        val_ratio=args.val_ratio,
        test_ratio=args.test_ratio,
        min_oracles_for_test=args.min_oracles_for_test,
        seed=args.seed
    )

    # 4) Persist project lists and summary
    write_lists(out_dir, splits)
    write_summary(out_dir, projects, splits)

    # 5) Optionally materialize test subsets
    if args.materialize_test:
        test_subsets_dir = out_dir / "test_subsets"
        safe_mkdir(test_subsets_dir)
        for proj in splits["test"]:
            indices = select_time_aware_indices(
                proj["path"], proj["ext"],
                args.timestamp_field,
                args.test_tail_frac,
                args.min_test_per_project
            )
            if indices:
                dest_name = f"{proj['project_id']}_test{proj['ext']}"
                materialize_test_subset(proj["path"], test_subsets_dir / dest_name, indices, proj["ext"])

    print("Done.")
    print(f"- Train projects: {len(splits['train'])}")
    print(f"- Val projects: {len(splits['val'])}")
    print(f"- Test projects: {len(splits['test'])}")
    print(f"Outputs in: {out_dir}")


if __name__ == "__main__":
    main()
