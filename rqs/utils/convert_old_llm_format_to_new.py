#!/usr/bin/env python3
"""
Convert Old Format (info.json + oracles.csv) to New JSON Format

This script merges data from an info.json file and an oracles.csv file
to create individual JSON files in the new format.

Usage:
    python convert_old_to_new.py <info_json> <oracles_csv> <output_dir>

Example:
    python convert_old_to_new.py data_info.json data_oracles.csv output/
"""

import json, csv, argparse, sys, re, os

# Increase CSV field size limit to maximum possible
field_size_limit = sys.maxsize
while True:
    try:
        csv.field_size_limit(field_size_limit)
        break
    except OverflowError:
        field_size_limit = int(field_size_limit / 10)


def extract_oracle_from_tags(text):
    """
    Extract oracle content from [oracle]...[/oracle] tags.
    If no tags found, return the original text.
    """
    pattern = r'\[oracle\](.*?)\[/oracle\]'
    match = re.search(pattern, text, re.DOTALL)
    if match:
        return match.group(1).strip()
    return text.strip()


def load_info_json(info_path):
    """Load the info JSON file and create a dictionary keyed by id."""
    with open(info_path, 'r', encoding='utf-8') as f:
        info_list = json.load(f)

    # Create a dictionary keyed by id for fast lookup
    info_dict = {}
    for item in info_list:
        if 'id' in item:
            info_dict[item['id']] = item

    return info_dict


def load_oracles_csv(csv_path):
    """Load the oracles CSV file and create a dictionary keyed by test_id."""
    oracles_dict = {}

    with open(csv_path, 'r', encoding='utf-8') as f:
        # CSV has no header, read as raw rows
        csv_reader = csv.reader(f)

        for row in csv_reader:
            if len(row) >= 7:  # Ensure we have enough columns
                test_id = row[0]
                oracles_dict[test_id] = {
                    'generated': row[4],
                    'time': row[5]
                }

    return oracles_dict


def parse_test_id(test_id):
    """
    Parse test_id to extract class_name and test_name.
    Format: ClassName-testName-number
    """
    parts = test_id.split('-')

    if len(parts) >= 2:
        class_name = parts[0]
        test_name = parts[1]
    else:
        print("Warning: the classname and the test name could not be estracted from the identifier. Set to empty strings", file=sys.stderr)
        # Fallback if format is different
        class_name = ""
        test_name = ""

    return class_name, test_name


def merge_data(info_dict, oracles_dict):
    """
    Merge info.json data with oracles.csv data.
    Returns a list of records in the new format.
    """
    merged_records = []

    for test_id, oracle_data in oracles_dict.items():
        # Check if this test_id exists in oracles CSV
        if test_id not in info_dict:
            print(f"Warning: test_id '{test_id}' not found in oracles CSV, skipping...", file=sys.stderr)
            continue
        info = info_dict[test_id]
        # Parse test_id to get class_name and test_name
        class_name, test_name = parse_test_id(test_id)
        # Extract generated oracle (remove [oracle] tags if present)
        generated = extract_oracle_from_tags(oracle_data['generated'])
        # Create tp_body by concatenating signature and tp_body
        signature = info.get('signature', '')
        tp_body_original = info.get('tp_body', '')
        # Format tp_body: signature + "\n" + tp_body
        tp_body_combined = f"{signature}{tp_body_original}"
        # Create the new record
        new_record = {
            'test_id': test_id,
            'test_class_path': info.get('test_class_path', ''),
            'class_name': class_name,
            'test_name': test_name,
            'signature': signature,
            'tp_body': tp_body_combined,
            'tgt': info.get('tgt', ''),
            'generated': generated,
            'status': '',
            'time': oracle_data['time'],
            'message': ''
        }
        merged_records.append(new_record)
    return merged_records


def convert(args, input_csv_file_path, info_json_file_path, output_file_path):

    if not os.path.exists(info_json_file_path):
        print(f"Error: info.json file '{info_json_file_path}' not found", file=sys.stderr)
        sys.exit(1)

    if not os.path.exists(input_csv_file_path):
        print(f"Error: oracles.csv file '{input_csv_file_path}' not found", file=sys.stderr)
        sys.exit(1)

    # Load data
    print("Loading info.json...")
    info_dict = load_info_json(info_json_file_path)
    print(f"✓ Loaded {len(info_dict)} records from info.json")
    print("Loading oracles.csv...")
    oracles_dict = load_oracles_csv(input_csv_file_path)
    print(f"✓ Loaded {len(oracles_dict)} records from oracles.csv")
    # Merge data
    print("Merging data...")
    merged_records = merge_data(info_dict, oracles_dict)
    print(f"✓ Successfully merged {len(merged_records)} records")
    with open(output_file_path, 'w', encoding='utf-8') as f:
        json.dump(merged_records, f, indent=2, ensure_ascii=False)
    print(f"✓ Saved {len(merged_records)} individual JSON files to {output_file_path}")
    # Report any mismatches
    matched_ids = set(record['test_id'] for record in merged_records)
    info_ids = set(info_dict.keys())
    oracle_ids = set(oracles_dict.keys())
    unmatched_info = info_ids - matched_ids
    unmatched_oracle = oracle_ids - matched_ids
    if unmatched_info:
        print(f"\nWarning: {len(unmatched_info)} test_ids in info.json were not found in oracles.csv")
    if unmatched_oracle:
        print(f"Warning: {len(unmatched_oracle)} test_ids in oracles.csv were not found in info.json")
    print(f"\n✓ Conversion complete!")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Convert old format (info.json + oracles.csv) to new JSON format',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument(
        '--input_csv_path',
        type=str,
        help='Path to input CSV files'
    )
    parser.add_argument(
        '--input_json_path',
        type=str,
        help='Path to input JSON files'
    )
    parser.add_argument(
        '--output_path',
        type=str,
        help='Path to output JSON files'
    )
    args = parser.parse_args()

    # Collect files to process
    if os.path.isdir(args.input_csv_path):
        csv_files = []
        for root, dirs, files in os.walk(args.input_csv_path):
            for file in files:
                if file.endswith('.csv'):
                    csv_files.append(os.path.join(root, file))
    else:
        csv_files = [args.input_csv_path]

    if not os.path.exists(args.output_path):
        os.makedirs(args.output_path)

    for input_csv_file_path in csv_files:
        info_json_filename = os.path.basename(input_csv_file_path).replace('.csv', '_info.json')
        info_json_file_path = os.path.join(args.input_json_path, info_json_filename)
        if not os.path.exists(info_json_file_path):
            print(f"Error: Info.json file `{info_json_filename}` not found (it should match `{input_csv_file_path}`)", file=sys.stderr)
        repo_id = info_json_filename.replace("oracles-datapoints-","").split("-")[0]
        idx = input_csv_file_path.replace("oracles-datapoints-","").split("-")[1]
        output_file_path = os.path.join(args.output_path,"llm-" + repo_id + "-oracles.json")
        print(f'Processing file {os.path.basename(input_csv_file_path)} and corresponding {os.path.basename(info_json_file_path)} from repo id {repo_id} (the final json will be save here: {output_file_path})')
        convert(args, input_csv_file_path, info_json_file_path, output_file_path)