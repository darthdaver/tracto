#!/usr/bin/env python3
"""
CSV Source Code Substring Search

This script reads all CSV files within a specified folder, searches for occurrences of a target substring
within the 'src' column, and returns a JSON list containing dictionaries with file name, row index,
and source content for each occurrence found.

Usage:
    python csv_substring_search.py /path/to/csv/folder "target_substring"

Or use the function directly:
    from csv_substring_search import search_substring_in_csv_files
    results = search_substring_in_csv_files("/path/to/csv/folder", "target_substring")
"""

import os
import pandas as pd
import json
import sys
import re
from pathlib import Path
from typing import List, Dict, Any, Optional


def search_substring_in_csv_files(folder_path: str, target_substring: str,
                                 case_sensitive: bool = False,
                                 use_regex: bool = False) -> List[Dict[str, Any]]:
    """
    Reads all CSV files in a folder and searches for a target substring in the 'src' column.

    Args:
        folder_path (str): Path to the folder containing CSV files
        target_substring (str): The substring to search for in the 'src' column
        case_sensitive (bool): Whether the search should be case-sensitive (default: False)
        use_regex (bool): Whether to treat target_substring as a regex pattern (default: False)

    Returns:
        List[Dict[str, Any]]: List of dictionaries with the following structure:
            - file_name (str): Name of the CSV file
            - row_index (int): Zero-based row index in the original file
            - src_content (str): Complete content of the 'src' column for that row

    Raises:
        FileNotFoundError: If the specified folder doesn't exist
        ValueError: If no CSV files are found in the folder or target_substring is empty
    """

    # Validate inputs
    if not target_substring or target_substring.strip() == "":
        raise ValueError("Target substring cannot be empty")

    folder_path = Path(folder_path)
    if not folder_path.exists():
        raise FileNotFoundError(f"Folder not found: {folder_path}")

    if not folder_path.is_dir():
        raise ValueError(f"Path is not a directory: {folder_path}")

    # Initialize result list
    result_list = []

    # Get all CSV files in the folder
    csv_files = list(folder_path.glob("*.csv"))

    if not csv_files:
        raise ValueError(f"No CSV files found in {folder_path}")

    print(f"Found {len(csv_files)} CSV file(s) to process")
    print(f"Searching for: '{target_substring}' (case_sensitive={case_sensitive}, use_regex={use_regex})")

    # Compile regex pattern if needed
    if use_regex:
        try:
            flags = 0 if case_sensitive else re.IGNORECASE
            pattern = re.compile(target_substring, flags)
        except re.error as e:
            raise ValueError(f"Invalid regex pattern '{target_substring}': {str(e)}")

    # Process each CSV file
    total_occurrences = 0
    for csv_file in csv_files:
        try:
            print(f"Processing: {csv_file.name}")

            # Read the CSV file
            df = pd.read_csv(csv_file)

            # Check if 'src' column exists
            if 'src' not in df.columns:
                print(f"Warning: 'src' column not found in {csv_file.name}. Available columns: {list(df.columns)}")
                continue

            file_occurrences = 0

            # Process each row with 'src' column data
            for row_index,row in enumerate(df[['src', 'tgt', 'unmask_tokens']].itertuples(index=False)):
                src_value, tgt, unmask_tokens = row
                # Skip NaN/null values
                if pd.isna(src_value):
                    continue

                src_str = str(src_value)

                # Check if the target substring is found
                found = False
                if use_regex:
                    found = bool(pattern.search(src_str))
                else:
                    if case_sensitive:
                        found = target_substring in src_str
                    else:
                        found = target_substring.lower() in src_str.lower()

                if found:
                    # Create dictionary for this occurrence
                    try:
                        if "# NEXT TOKEN CANDIDATES" in src_str:
                            next_candidates = src_str.strip().split("# NEXT TOKEN CANDIDATES")[1].split("# TASK")[0]
                        else:
                            next_candidates = ""

                        if "{\"choice\":\"<candidate>\"}" in src_str:
                            stage = "selection"
                        else:
                            stage = "selection"

                        occurrence = {
                            "file_name": csv_file.name,
                            "row_index": row_index,
                            "src_content": src_str.strip().split("<|fim_prefix|>")[1].split("<|fim_suffix|>")[0],
                            "next_candidate_tokens_str": next_candidates,
                            "stage": stage,
                            "tgt": str(row.tgt) if not pd.isna(row.tgt) else "",
                            "unmask_tokens": unmask_tokens if not pd.isna(unmask_tokens) else []
                        }
                        result_list.append(occurrence)
                        file_occurrences += 1
                        total_occurrences += 1
                    except Exception as e:
                        print(f"  Error processing row {row_index} in {csv_file.name}: {str(e)}")
                        continue

            if file_occurrences > 0:
                print(f"  Found {file_occurrences} occurrence(s) in {csv_file.name}")
            else:
                print(f"  No occurrences found in {csv_file.name}")

        except Exception as e:
            print(f"Error processing {csv_file.name}: {str(e)}")
            continue

    print(f"\nTotal occurrences found: {total_occurrences}")
    return result_list


def save_results_to_json(results: List[Dict[str, Any]], output_file: str = "substring_search_results.json") -> None:
    """
    Save the results to a JSON file.

    Args:
        results: List of dictionaries from search_substring_in_csv_files
        output_file: Path for the output JSON file
    """
    try:
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(results, f, indent=2, ensure_ascii=False)
        print(f"Results saved to: {output_file}")
    except Exception as e:
        print(f"Error saving results to JSON: {str(e)}")


def main():
    """Main function for command-line usage."""
    if len(sys.argv) < 3:
        print("Usage: python csv_substring_search.py <folder_path> <target_substring> [options]")
        print("\nArguments:")
        print("  folder_path      Path to the folder containing CSV files")
        print("  target_substring The substring to search for in the 'src' column")
        print("\nOptional flags:")
        print("  --case-sensitive Enable case-sensitive search")
        print("  --regex         Treat target_substring as a regular expression")
        print("\nExamples:")
        print("  python csv_substring_search.py /path/to/csv 'function'")
        print("  python csv_substring_search.py /path/to/csv 'import.*numpy' --regex")
        print("  python csv_substring_search.py /path/to/csv 'MyClass' --case-sensitive")
        sys.exit(1)

    folder_path = sys.argv[1]
    target_substring = sys.argv[2]
    # Parse optional flags
    case_sensitive = '--case-sensitive' in sys.argv
    use_regex = '--regex' in sys.argv

    try:
        # Search for substring in CSV files
        results = search_substring_in_csv_files(
            folder_path,
            target_substring,
            case_sensitive=case_sensitive,
            use_regex=use_regex
        )
        # Display results
        print("\n" + "="*60)
        print("SEARCH RESULTS:")
        print("="*60)

        if results:
            json_output = json.dumps(results, indent=2, ensure_ascii=False)
            print(json_output)
            # Save to file
            save_results_to_json(results)
        else:
            print("No occurrences found.")
    except Exception as e:
        print(f"Error: {str(e)}")
        sys.exit(1)


if __name__ == "__main__":
    main()
