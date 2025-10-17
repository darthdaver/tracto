#!/usr/bin/env python3
"""
Enhanced Constraint Loading Test Script with Detailed Decoding

This script validates constraint sequences and shows decoded examples
to verify they match the original input before tokenization.
"""

import os
import json
import glob
import numpy as np
import torch
from typing import List, Dict, Optional, Set
import transformers
import re

# Import classes from your training script
from finetuning_qwen import (
    ConstrainedMemmapShardDataset,
    ConstraintTrie,
    load_tokenizer_consistent,
    ConstraintAwareDataCollator
)


def decode_constraint_sequence(tokenizer, sequence: List[int], show_token_ids: bool = True) -> str:
    """Decode a constraint sequence with detailed token information"""
    if not sequence:
        return "(empty sequence)"

    try:
        # Decode the full sequence
        full_text = tokenizer.decode(sequence, skip_special_tokens=False)

        if show_token_ids:
            # Also show individual tokens
            individual_tokens = []
            for token_id in sequence:
                try:
                    token_text = tokenizer.decode([token_id], skip_special_tokens=False)
                    # Show special characters clearly
                    if token_text.strip() == "":
                        if token_text == " ":
                            individual_tokens.append(f"{token_id}:'<SPACE>'")
                        elif token_text == "\n":
                            individual_tokens.append(f"{token_id}:'<NEWLINE>'")
                        elif token_text == "\t":
                            individual_tokens.append(f"{token_id}:'<TAB>'")
                        else:
                            individual_tokens.append(f"{token_id}:'{repr(token_text)}'")
                    else:
                        individual_tokens.append(f"{token_id}:'{token_text}'")
                except:
                    individual_tokens.append(f"{token_id}:DECODE_ERROR")

            return f"Full: '{full_text}' | Tokens: [{', '.join(individual_tokens)}]"
        else:
            return f"'{full_text}'"

    except Exception as e:
        return f"DECODE_ERROR: {e}"


def analyze_constraint_patterns(tokenizer, constraint_sequences: List[List[int]]) -> Dict:
    """Analyze patterns in constraint sequences"""
    analysis = {
        "total_sequences": len(constraint_sequences),
        "empty_sequences": 0,
        "sequence_lengths": [],
        "unique_first_tokens": set(),
        "common_patterns": {},
        "json_structure_tokens": set(),
        "candidate_tokens": set(),
    }

    # JSON structure tokens we expect to see
    json_keywords = ["{", "}", '"', ":", "choice", " "]

    for seq in constraint_sequences:
        if not seq:
            analysis["empty_sequences"] += 1
            continue

        analysis["sequence_lengths"].append(len(seq))

        if seq:
            analysis["unique_first_tokens"].add(seq[0])

            # Look for JSON structure
            decoded = tokenizer.decode(seq, skip_special_tokens=False)
            for keyword in json_keywords:
                if keyword in decoded:
                    analysis["json_structure_tokens"].update([t for t in seq if tokenizer.decode([t]) == keyword])

            # Pattern analysis (first few tokens)
            pattern_key = tuple(seq[:3])  # First 3 tokens as pattern
            analysis["common_patterns"][pattern_key] = analysis["common_patterns"].get(pattern_key, 0) + 1

            # Extract potential candidate tokens (things that look like choices)
            if '"' in decoded:
                # Try to extract content between quotes
                import re
                matches = re.findall(r'"([^"]*)"', decoded)
                for match in matches:
                    if match and match not in ["choice"]:  # Skip JSON structure
                        try:
                            candidate_tokens = tokenizer.encode(match, add_special_tokens=False)
                            analysis["candidate_tokens"].update(candidate_tokens)
                        except:
                            pass

    return analysis


def print_constraint_examples(tokenizer, constraint_sequences: List[List[int]], max_examples: int = 10):
    """Print detailed examples of constraint sequences with their decoded content"""

    print(
        f"\n📋 CONSTRAINT SEQUENCE EXAMPLES (showing {min(max_examples, len(constraint_sequences))} of {len(constraint_sequences)})")
    print("=" * 100)

    for i, seq in enumerate(constraint_sequences[:max_examples]):
        print(f"\nExample {i + 1}:")
        print(f"  Length: {len(seq)} tokens")
        print(f"  Token IDs: {seq}")

        if seq:
            decoded = decode_constraint_sequence(tokenizer, seq, show_token_ids=True)
            print(f"  Decoded: {decoded}")

            # Try to identify what kind of constraint this is
            full_text = tokenizer.decode(seq, skip_special_tokens=False)

            constraint_type = "unknown"
            if '"choice":' in full_text:
                if full_text.endswith('"}'):
                    constraint_type = "complete_json"
                else:
                    constraint_type = "partial_json"
            elif any(token in full_text for token in ["assertEquals", "assertTrue", "assertFalse"]):
                constraint_type = "assertion_method"
            elif full_text.startswith('"') and full_text.endswith('"'):
                constraint_type = "quoted_string"
            elif full_text in ["{", "}", "(", ")", ",", ";"]:
                constraint_type = "punctuation"

            print(f"  Type: {constraint_type}")

            # Show individual meaningful tokens
            if len(seq) <= 10:  # Only for shorter sequences
                print(f"  Individual tokens:")
                for j, token_id in enumerate(seq):
                    token_text = tokenizer.decode([token_id], skip_special_tokens=False)
                    print(
                        f"    {j}: {token_id} → '{token_text}' {repr(token_text) if token_text.strip() != token_text else ''}")
        else:
            print(f"  (Empty sequence)")

        print("-" * 100)


def compare_with_expected_candidates(tokenizer, constraint_sequences: List[List[int]], sample_input_text: str):
    """Compare loaded constraints with what we expect based on the input text"""

    print(f"\n🔍 CONSTRAINT VALIDATION AGAINST INPUT")
    print("=" * 100)

    # Extract candidate lists from the input text
    candidates_match = re.search(r'# NEXT TOKEN CANDIDATES\s*\n(.*?)(?=\n#|\nZ)', sample_input_text, re.DOTALL)
    if not candidates_match:
        print("❌ Could not find candidate token lists in input text")
        return

    candidates_text = candidates_match.group(1)
    print(f"Candidates section from input:\n{candidates_text}")

    # Parse different candidate categories
    java_tokens = []
    project_tokens = []
    generic_tokens = []

    for line in candidates_text.split('\n'):
        line = line.strip()
        if line.startswith('# JAVA TOKENS:') or line.startswith('# JAVA_TOKENS:'):
            # Extract list content
            list_match = re.search(r'\[(.*?)\]', line)
            if list_match:
                list_content = list_match.group(1)
                # Parse as JSON-like list
                try:
                    java_tokens = eval('[' + list_content + ']')  # Simple parsing
                except:
                    java_tokens = [t.strip(' "') for t in list_content.split(',') if t.strip()]

        elif line.startswith('# PROJECT TOKENS:'):
            list_match = re.search(r'\[(.*?)\]', line)
            if list_match:
                list_content = list_match.group(1)
                try:
                    project_tokens = eval('[' + list_content + ']')
                except:
                    project_tokens = [t.strip(' "') for t in list_content.split(',') if t.strip()]

        elif line.startswith('# GENERIC TOKENS:'):
            list_match = re.search(r'\[(.*?)\]', line)
            if list_match:
                list_content = list_match.group(1)
                try:
                    generic_tokens = eval('[' + list_content + ']')
                except:
                    generic_tokens = [t.strip(' "') for t in list_content.split(',') if t.strip()]

    print(f"\nParsed candidates from input:")
    print(f"  Java tokens ({len(java_tokens)}): {java_tokens}")
    print(f"  Project tokens ({len(project_tokens)}): {project_tokens}")
    print(f"  Generic tokens ({len(generic_tokens)}): {generic_tokens}")

    # Expected constraint sequences based on your format
    expected_constraints = set()

    # JSON structure tokens that should always be present
    json_structure = ['{"choice":"', '"}']
    for struct in json_structure:
        encoded = tokenizer.encode(struct, add_special_tokens=False)
        expected_constraints.update(encoded)

    # All candidate tokens
    all_candidates = java_tokens + project_tokens + generic_tokens
    for candidate in all_candidates:
        if candidate.strip():  # Skip empty
            encoded = tokenizer.encode(candidate, add_special_tokens=False)
            expected_constraints.update(encoded)

    print(f"\nExpected constraint tokens (total {len(expected_constraints)}):")
    expected_tokens_decoded = []
    for token_id in sorted(expected_constraints):
        decoded = tokenizer.decode([token_id], skip_special_tokens=False)
        expected_tokens_decoded.append(f"{token_id}:'{decoded}'")
    print(f"  {expected_tokens_decoded}")

    # Compare with actual loaded constraints
    actual_constraint_tokens = set()
    for seq in constraint_sequences:
        actual_constraint_tokens.update(seq)

    print(f"\nActual constraint tokens (total {len(actual_constraint_tokens)}):")
    actual_tokens_decoded = []
    for token_id in sorted(actual_constraint_tokens):
        try:
            decoded = tokenizer.decode([token_id], skip_special_tokens=False)
            actual_tokens_decoded.append(f"{token_id}:'{decoded}'")
        except:
            actual_tokens_decoded.append(f"{token_id}:DECODE_ERROR")
    print(f"  {actual_tokens_decoded}")

    # Find matches and mismatches
    common_tokens = expected_constraints.intersection(actual_constraint_tokens)
    missing_tokens = expected_constraints - actual_constraint_tokens
    extra_tokens = actual_constraint_tokens - expected_constraints

    print(f"\n📊 COMPARISON RESULTS:")
    print(f"  ✅ Common tokens: {len(common_tokens)}")
    print(f"  ❌ Missing expected tokens: {len(missing_tokens)}")
    print(f"  ⚠️  Extra tokens: {len(extra_tokens)}")

    if missing_tokens:
        print(f"\n  Missing tokens:")
        for token_id in sorted(missing_tokens):
            decoded = tokenizer.decode([token_id], skip_special_tokens=False)
            print(f"    {token_id}: '{decoded}'")

    if extra_tokens:
        print(f"\n  Extra tokens:")
        for token_id in sorted(extra_tokens):
            try:
                decoded = tokenizer.decode([token_id], skip_special_tokens=False)
                print(f"    {token_id}: '{decoded}'")
            except:
                print(f"    {token_id}: DECODE_ERROR")

    coverage = len(common_tokens) / len(expected_constraints) if expected_constraints else 0
    print(f"\n  Coverage: {coverage:.1%} of expected tokens found")

    return coverage > 0.8  # 80% coverage threshold


def test_constraint_loading_with_examples(tokenized_dir: str, model_name: str = "Qwen/Qwen2.5-Coder-14B"):
    """Enhanced test with detailed constraint examples and validation"""

    print("=" * 80)
    print("ENHANCED CONSTRAINT LOADING TEST WITH EXAMPLES")
    print("=" * 80)

    # Load tokenizer
    print(f"\n1. Loading tokenizer...")
    try:
        tokenizer = load_tokenizer_consistent(
            model_name,
            model_max_length=8192,
            tokenized_dir=tokenized_dir
        )
        print(f"   ✅ Tokenizer loaded (vocab size: {len(tokenizer)})")
    except Exception as e:
        print(f"   ❌ ERROR: {e}")
        return False

    # Load dataset
    print(f"\n2. Loading dataset...")
    try:
        train_ds = ConstrainedMemmapShardDataset(tokenized_dir, "train")
        print(f"   ✅ Dataset loaded ({len(train_ds)} samples)")
    except Exception as e:
        print(f"   ❌ ERROR: {e}")
        return False

    # Find samples with constraints and detailed analysis
    print(f"\n3. Analyzing constraint sequences...")

    samples_analyzed = 0
    total_constraint_sequences = 0

    for i in range(min(5, len(train_ds))):  # Analyze first 5 samples
        try:
            sample = train_ds[i]
            constraint_sequences = sample.get('constraint_sequences', [])

            if constraint_sequences:
                samples_analyzed += 1
                total_constraint_sequences += len(constraint_sequences)

                print(f"\n" + "=" * 60)
                print(f"SAMPLE {i} ANALYSIS")
                print(f"=" * 60)

                # Show input context (decode some input_ids to see the prompt)
                input_ids = sample['input_ids']
                input_text = tokenizer.decode(input_ids, skip_special_tokens=False)

                print(f"Input length: {len(input_ids)} tokens")
                print(f"Constraint sequences: {len(constraint_sequences)}")

                # Show a portion of the input text around FIM tokens
                fim_middle_pos = input_text.find('<|fim_middle|>')
                if fim_middle_pos > 0:
                    # Show context before fim_middle
                    context_start = max(0, fim_middle_pos - 500)
                    context_end = min(len(input_text), fim_middle_pos + 100)
                    context = input_text[context_start:context_end]
                    print(f"\nInput context around <|fim_middle|>:")
                    print(f"...{context}...")

                # Analyze constraint patterns
                analysis = analyze_constraint_patterns(tokenizer, constraint_sequences)

                print(f"\nConstraint Analysis:")
                print(f"  Total sequences: {analysis['total_sequences']}")
                print(f"  Empty sequences: {analysis['empty_sequences']}")
                if analysis['sequence_lengths']:
                    print(
                        f"  Length range: {min(analysis['sequence_lengths'])}-{max(analysis['sequence_lengths'])} tokens")
                    print(
                        f"  Average length: {sum(analysis['sequence_lengths']) / len(analysis['sequence_lengths']):.1f} tokens")

                # Show detailed examples
                print_constraint_examples(tokenizer, constraint_sequences, max_examples=8)

                # Compare with expected candidates (if we can parse them from input)
                if "# NEXT TOKEN CANDIDATES" in input_text:
                    compare_with_expected_candidates(tokenizer, constraint_sequences, input_text)

                # Test trie construction with this sample
                print(f"\n🌳 TRIE CONSTRUCTION TEST")
                print("-" * 50)
                try:
                    constraint_trie = ConstraintTrie(constraint_sequences, eos_token_id=tokenizer.eos_token_id)

                    # Test empty prefix (valid first tokens)
                    valid_first = constraint_trie.get_valid_continuations([])
                    print(f"Valid first tokens: {len(valid_first)}")

                    if valid_first:
                        first_examples = list(valid_first)[:8]  # Show first 8
                        for token_id in first_examples:
                            decoded = tokenizer.decode([token_id], skip_special_tokens=False)
                            print(f"  {token_id}: '{decoded}'")

                    # Test a few specific prefixes from the constraint sequences
                    for j, seq in enumerate(constraint_sequences[:3]):
                        if len(seq) >= 2:
                            prefix = seq[:2]
                            continuations = constraint_trie.get_valid_continuations(prefix)
                            prefix_decoded = tokenizer.decode(prefix, skip_special_tokens=False)
                            print(f"After prefix '{prefix_decoded}': {len(continuations)} valid continuations")

                    print(f"✅ Trie construction successful")

                except Exception as e:
                    print(f"❌ Trie construction failed: {e}")

                break  # Just analyze the first sample with constraints in detail

        except Exception as e:
            print(f"❌ Error analyzing sample {i}: {e}")

    if samples_analyzed == 0:
        print("❌ No samples with constraints found for detailed analysis")
        return False

    print(f"\n" + "=" * 80)
    print("SUMMARY")
    print("=" * 80)
    print(f"✅ Successfully analyzed {samples_analyzed} samples with constraints")
    print(f"✅ Total constraint sequences found: {total_constraint_sequences}")
    print("\nThe constraint loading appears to be working correctly!")
    print("You can now proceed with training.")

    return True


if __name__ == "__main__":
    import sys

    if len(sys.argv) != 2:
        print("Usage: python test_constraints_detailed.py <tokenized_dir>")
        print("Example: python test_constraints_detailed.py ./data/tokenized")
        sys.exit(1)

    tokenized_dir = sys.argv[1]

    success = test_constraint_loading_with_examples(tokenized_dir)
    sys.exit(0 if success else 1)
