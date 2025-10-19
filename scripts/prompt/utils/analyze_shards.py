#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Shard Constraint Analyzer

This script reads tokenized shards and analyzes the constraint sequences
to identify inconsistencies in tokenization, especially in prefixes.

Usage:
    python analyze_shards.py --shard_dir /path/to/tokenized --split train --num_samples 10
"""

import os
import glob
import json
import argparse
import numpy as np
from collections import defaultdict, Counter
from typing import List, Tuple, Dict, Optional
from transformers import AutoTokenizer


class ShardReader:
    """Read constraint sequences from binary shard files."""

    def __init__(self, shard_dir: str, split: str):
        self.shard_dir = shard_dir
        self.split = split
        self.shards = []
        self._load_shard_metadata()

    def _load_shard_metadata(self):
        """Load all shard files for the given split."""
        pattern = os.path.join(self.shard_dir, f"{self.split}.*.idx")
        idx_files = sorted(glob.glob(pattern))

        main_idx_files = [f for f in idx_files if not f.endswith('.constraints.idx')]

        if not main_idx_files:
            raise FileNotFoundError(f"No {self.split} shards found in {self.shard_dir}")

        for idx_path in main_idx_files:
            base = os.path.splitext(idx_path)[0]  # Remove .idx
            constraints_path = base + '.constraints.bin'
            constraints_idx_path = base + '.constraints.idx'

            if os.path.exists(constraints_path) and os.path.exists(constraints_idx_path):
                self.shards.append({
                    'base': base,
                    'constraints_path': constraints_path,
                    'constraints_idx_path': constraints_idx_path,
                    'idx_path': idx_path
                })

        print(f"✅ Loaded {len(self.shards)} shards for split '{self.split}'")

    def read_constraints_for_sample(self, shard_idx: int, sample_idx: int) -> List[List[int]]:
        """Read constraint sequences for a specific sample."""
        if shard_idx >= len(self.shards):
            raise IndexError(f"Shard index {shard_idx} out of range")

        shard = self.shards[shard_idx]
        constraints_path = shard['constraints_path']
        constraints_idx_path = shard['constraints_idx_path']

        # Load index
        constraints_dtype = np.dtype([('offset', np.uint64), ('length', np.uint32)])
        with open(constraints_idx_path, 'rb') as f:
            idx_array = np.fromfile(f, dtype=constraints_dtype)

        if sample_idx >= len(idx_array):
            raise IndexError(f"Sample index {sample_idx} out of range (max: {len(idx_array)-1})")

        offset, length = idx_array[sample_idx]

        if length == 0:
            return []

        # Read constraint data
        with open(constraints_path, 'rb') as f:
            f.seek(offset)
            data = f.read(length)

        # Parse binary data
        constraint_seqs = []
        pos = 0

        # Read number of sequences
        num_seqs = np.frombuffer(data[pos:pos+4], dtype=np.uint32)[0]
        pos += 4

        # Read each sequence
        for _ in range(num_seqs):
            if pos + 4 > len(data):
                break

            seq_len = np.frombuffer(data[pos:pos+4], dtype=np.uint32)[0]
            pos += 4

            if pos + seq_len * 4 > len(data):
                break

            seq = np.frombuffer(data[pos:pos+seq_len*4], dtype=np.int32).tolist()
            pos += seq_len * 4

            constraint_seqs.append(seq)

        return constraint_seqs

    def get_num_samples_in_shard(self, shard_idx: int) -> int:
        """Get the number of samples in a shard."""
        shard = self.shards[shard_idx]
        constraints_dtype = np.dtype([('offset', np.uint64), ('length', np.uint32)])
        with open(shard['constraints_idx_path'], 'rb') as f:
            idx_array = np.fromfile(f, dtype=constraints_dtype)
        return len(idx_array)


class ConstraintAnalyzer:
    """Analyze constraint sequences for tokenization consistency."""

    def __init__(self, tokenizer_path: str):
        self.tokenizer = AutoTokenizer.from_pretrained(
            tokenizer_path, 
            trust_remote_code=True
        )
        self.prefix_variants = defaultdict(int)
        self.all_sequences = []

    def analyze_sample(self, constraint_seqs: List[List[int]]) -> Dict:
        """Analyze a single sample's constraints."""
        if not constraint_seqs:
            return {'has_constraints': False}

        analysis = {
            'has_constraints': True,
            'num_sequences': len(constraint_seqs),
            'prefix_lengths': [],
            'prefixes': []
        }

        # Find common prefix length
        for seq in constraint_seqs:
            # Try to identify the prefix
            # Most sequences should start with '{"choice":"' or similar
            if len(seq) >= 3:
                prefix = tuple(seq[:3])
                analysis['prefixes'].append(prefix)
                analysis['prefix_lengths'].append(len(seq))

        return analysis

    def analyze_batch(self, reader: ShardReader, shard_idx: int, 
                     start_sample: int, num_samples: int) -> Dict:
        """Analyze multiple samples from a shard."""
        print(f"\n{'='*80}")
        print(f"Analyzing Shard {shard_idx}, Samples {start_sample} to {start_sample + num_samples - 1}")
        print(f"{'='*80}\n")

        results = {
            'total_samples': 0,
            'samples_with_constraints': 0,
            'prefix_distribution': Counter(),
            'inconsistent_samples': [],
            'example_sequences': []
        }

        max_samples = reader.get_num_samples_in_shard(shard_idx)
        actual_num_samples = min(num_samples, max_samples - start_sample)

        for i in range(actual_num_samples):
            sample_idx = start_sample + i
            try:
                constraints = reader.read_constraints_for_sample(shard_idx, sample_idx)

                if not constraints:
                    continue

                results['total_samples'] += 1
                results['samples_with_constraints'] += 1

                # Collect prefixes
                prefixes_in_sample = set()
                for seq in constraints:
                    if len(seq) >= 3:
                        prefix = tuple(seq[:3])
                        prefixes_in_sample.add(prefix)
                        results['prefix_distribution'][prefix] += 1

                # Check for inconsistency within sample
                if len(prefixes_in_sample) > 1:
                    results['inconsistent_samples'].append({
                        'sample_idx': sample_idx,
                        'prefixes': list(prefixes_in_sample),
                        'num_sequences': len(constraints)
                    })

                # Store example for first few samples
                if len(results['example_sequences']) < 3:
                    results['example_sequences'].append({
                        'sample_idx': sample_idx,
                        'sequences': constraints[:5]  # First 5 sequences only
                    })

            except Exception as e:
                print(f"⚠️  Error reading sample {sample_idx}: {e}")

        return results

    def print_analysis(self, results: Dict, tokenizer: AutoTokenizer):
        """Print detailed analysis results."""
        print(f"\n{'='*80}")
        print("ANALYSIS RESULTS")
        print(f"{'='*80}\n")

        print(f"📊 Summary:")
        print(f"  - Total samples analyzed: {results['total_samples']}")
        print(f"  - Samples with constraints: {results['samples_with_constraints']}")
        print(f"  - Inconsistent samples: {len(results['inconsistent_samples'])}")
        print()

        # Prefix distribution
        print(f"🔍 Prefix Distribution:")
        for prefix, count in results['prefix_distribution'].most_common():
            decoded = [tokenizer.decode([t]) for t in prefix]
            print(f"  - {prefix}: {count} occurrences")
            print(f"    Decoded: {decoded}")
        print()

        # Check for inconsistency
        if len(results['prefix_distribution']) > 1:
            print(f"⚠️  INCONSISTENCY DETECTED!")
            print(f"  - Found {len(results['prefix_distribution'])} different prefixes")
            print(f"  - This will cause constraint matching failures during training")
            print()
        else:
            print(f"✅ Prefix is CONSISTENT across all sequences")
            print()

        # Inconsistent samples
        if results['inconsistent_samples']:
            print(f"⚠️  Samples with Multiple Prefixes:")
            for item in results['inconsistent_samples'][:5]:  # Show first 5
                print(f"  - Sample {item['sample_idx']}: {len(item['prefixes'])} different prefixes")
                for prefix in item['prefixes']:
                    decoded = [tokenizer.decode([t]) for t in prefix]
                    print(f"    → {prefix}: {decoded}")
            print()

        # Example sequences
        print(f"📋 Example Sequences:\n")
        for example in results['example_sequences']:
            print(f"Sample {example['sample_idx']}:")
            for i, seq in enumerate(example['sequences'], 1):
                decoded_tokens = [tokenizer.decode([t]) for t in seq]
                full_decoded = tokenizer.decode(seq)
                print(f"  {i}. Tokens: {seq[:10]}{'...' if len(seq) > 10 else ''}")
                print(f"     Decoded: {decoded_tokens[:10]}{'...' if len(seq) > 10 else ''}")
                print(f"     Full: {repr(full_decoded)}")
            print()


def main():
    parser = argparse.ArgumentParser(
        description="Analyze constraint sequences in tokenized shards"
    )
    parser.add_argument('--shard_dir', required=True, 
                       help='Directory containing shard files')
    parser.add_argument('--split', default='train', 
                       help='Split to analyze (train/val)')
    parser.add_argument('--shard_idx', type=int, default=0,
                       help='Shard index to analyze')
    parser.add_argument('--start_sample', type=int, default=0,
                       help='Starting sample index')
    parser.add_argument('--num_samples', type=int, default=10,
                       help='Number of samples to analyze')
    parser.add_argument('--tokenizer', required=True,
                       help='Path or name of tokenizer to use for decoding')

    args = parser.parse_args()

    print(f"\n{'='*80}")
    print("SHARD CONSTRAINT ANALYZER")
    print(f"{'='*80}\n")
    print(f"📁 Shard directory: {args.shard_dir}")
    print(f"📊 Split: {args.split}")
    print(f"🔢 Analyzing shard {args.shard_idx}, samples {args.start_sample}-{args.start_sample + args.num_samples - 1}")
    print()

    # Load tokenizer
    print(f"Loading tokenizer from {args.tokenizer}...")
    tokenizer = AutoTokenizer.from_pretrained(args.tokenizer, trust_remote_code=True)
    print(f"✅ Tokenizer loaded (vocab size: {len(tokenizer)})\n")

    # Load shards
    reader = ShardReader(args.shard_dir, args.split)

    # Analyze
    analyzer = ConstraintAnalyzer(args.tokenizer)
    results = analyzer.analyze_batch(
        reader, 
        args.shard_idx, 
        args.start_sample, 
        args.num_samples
    )

    # Print results
    analyzer.print_analysis(results, tokenizer)

    # Final recommendation
    print(f"\n{'='*80}")
    print("RECOMMENDATION")
    print(f"{'='*80}\n")

    if len(results['prefix_distribution']) > 1:
        print("❌ INCONSISTENCY DETECTED!")
        print("\nYou need to:")
        print("1. Fix the encode_constraints_sequences() method in encode.py")
        print("2. Regenerate ALL shards with the fixed encoding")
        print("3. Re-run this analysis to verify the fix")
        print("\nSee the fix provided in the previous response.")
    else:
        print("✅ No inconsistencies detected in the analyzed samples")
        print("\nNote: This analysis checked {0} samples. For thorough validation,".format(
            results['total_samples']))
        print("consider analyzing more samples across different shards.")

    print()


if __name__ == '__main__':
    main()
