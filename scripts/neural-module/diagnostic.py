#!/usr/bin/env python3
"""
Simple diagnostic script to check file structure
"""

import os
import glob
import sys


def diagnose_file_structure(tokenized_dir: str):
    print(f"Diagnosing file structure in: {tokenized_dir}")
    print("=" * 80)

    # Check what files exist
    all_files = glob.glob(os.path.join(tokenized_dir, "*"))

    print("\nAll files found:")
    for file_path in sorted(all_files):
        file_name = os.path.basename(file_path)
        file_size = os.path.getsize(file_path) if os.path.isfile(file_path) else 0
        print(f"  {file_name} ({file_size:,} bytes)")

    # Look for specific patterns
    patterns = [
        "train.*.idx",
        "train.*.input_ids.bin",
        "train.*.labels.bin",
        "train.*.constraints.bin",
        "train.*.constraints.idx",
        "val.*.idx",
        "val.*.input_ids.bin",
        "val.*.labels.bin",
        "val.*.constraints.bin",
        "val.*.constraints.idx"
    ]

    print(f"\nFile pattern analysis:")
    for pattern in patterns:
        matches = glob.glob(os.path.join(tokenized_dir, pattern))
        print(f"  {pattern:<30}: {len(matches)} files")
        for match in matches[:3]:  # Show first 3
            print(f"    {os.path.basename(match)}")
        if len(matches) > 3:
            print(f"    ... and {len(matches) - 3} more")

    print(f"\nAnalyzing the issue...")

    # Find all .idx files
    idx_files = glob.glob(os.path.join(tokenized_dir, "*.idx"))

    print(f"Found {len(idx_files)} .idx files:")
    for idx_file in idx_files:
        print(f"  {os.path.basename(idx_file)}")

    # The issue: code is finding .constraints.idx as the main .idx file
    # Let's see what happens when we process them
    print(f"\nFile base analysis:")

    for idx_file in idx_files[:3]:  # Check first 3
        base = os.path.splitext(idx_file)[0]  # Remove .idx
        base_name = os.path.basename(base)

        print(f"\n  IDX file: {os.path.basename(idx_file)}")
        print(f"  Base becomes: {base_name}")

        # What files would the code look for?
        expected_files = [
            base + ".input_ids.bin",
            base + ".labels.bin",
        ]

        for expected in expected_files:
            exists = os.path.exists(expected)
            print(f"    Would look for: {os.path.basename(expected)} ({'✅' if exists else '❌'})")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python diagnose_files.py <tokenized_dir>")
        sys.exit(1)

    diagnose_file_structure(sys.argv[1])
