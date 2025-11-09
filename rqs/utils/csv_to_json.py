#!/usr/bin/env python3
"""
CSV to JSON Converter for Test Oracle Data
This script converts a CSV file containing test oracle information to JSON format.

CSV Format:
    test_id,test_class_path,class_name,test_name,signature,tp_body,tgt,generated,status,time,message

Output JSON Formats:
    1. Array format (default): List of test records
    2. Dictionary format: Keyed by test_id
    3. Grouped format: Grouped by class_name

Usage:
    python csv_to_json.py <input_csv> <output_json> [--format <array|dict|grouped>] [--indent <spaces>]

Examples:
    python csv_to_json.py input.csv output.json
    python csv_to_json.py input.csv output.json --format dict --indent 2
    python csv_to_json.py input.csv output.json --format grouped
"""

import csv
import json
import argparse
import sys
import os


def clean_row(row):
    cleaned_row = {
        key: ("" if value == '' or value == 'NaN' else value)
        for key, value in row.items()
    }
    # Convert numeric fields
    if cleaned_row.get('time'):
        try:
            cleaned_row['time'] = int(cleaned_row['time'])
        except ValueError:
            print(f"Unable to convert time for row: {cleaned_row['test_id']}")
            cleaned_row['time'] = -1
            pass
    return cleaned_row


def csv_to_json_array(csv_file_path):
    """
    Convert CSV to JSON array format.

    Args:
        csv_file_path: Path to input CSV file

    Returns:
        List of dictionaries representing each row
    """
    data = []
    with open(csv_file_path, 'r', encoding='utf-8') as csv_file:
        csv_reader = csv.DictReader(csv_file)
        for row in csv_reader:
            data.append(clean_row(row))
    return data


def csv_to_json_dict(csv_file_path):
    """
    Convert CSV to JSON dictionary format, keyed by test_id.

    Args:
        csv_file_path: Path to input CSV file

    Returns:
        Dictionary with test_id as keys
    """
    data = {}
    with open(csv_file_path, 'r', encoding='utf-8') as csv_file:
        csv_reader = csv.DictReader(csv_file)
        for row in csv_reader:
            test_id = row.pop('test_id')
            data[test_id] = clean_row(row)
    return data


def csv_to_json_grouped(csv_file_path):
    """
    Convert CSV to JSON grouped by class_name.

    Args:
        csv_file_path: Path to input CSV file

    Returns:
        Dictionary grouped by class_name
    """
    data = {}
    with open(csv_file_path, 'r', encoding='utf-8') as csv_file:
        csv_reader = csv.DictReader(csv_file)
        for row in csv_reader:
            class_name = row.get('class_name', 'Unknown')
            if class_name not in data:
                data[class_name] = []
            data[class_name].append(clean_row(row))
    return data


def convert(args, input_file_path, output_file_path):
    if not os.path.exists(input_file_path):
        print(f"Error: Input file '{input_file_path}' does not exist", file=sys.stderr)
        sys.exit(1)
    # Determine indentation
    indent = args.indent if args.indent > 0 else None
    if args.pretty:
        indent = 2
    # Convert based on format
    try:
        if args.format == 'array':
            data = csv_to_json_array(input_file_path)
        elif args.format == 'dict':
            data = csv_to_json_dict(input_file_path)
        elif args.format == 'grouped':
            data = csv_to_json_grouped(input_file_path)
        else:
            print(f"Error: Unknown format '{args.format}'", file=sys.stderr)
            sys.exit(1)
        # Write JSON output
        with open(output_file_path, 'w', encoding='utf-8') as json_file:
            json.dump(data, json_file, indent=indent, ensure_ascii=False)
        # Print statistics
        if args.format == 'array':
            print(f"✓ Successfully converted {len(data)} records to JSON array format")
        elif args.format == 'dict':
            print(f"✓ Successfully converted {len(data)} records to JSON dictionary format")
        elif args.format == 'grouped':
            total_records = sum(len(tests) for tests in data.values())
            print(f"✓ Successfully converted {total_records} records grouped into {len(data)} classes")
        print(f"✓ Output written to: {output_file_path}")
    except Exception as e:
        print(f"Error: {str(e)}", file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Convert CSV test oracle data to JSON format',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument(
        '--input_path',
        type=str,
        help='Path to input CSV file'
    )
    parser.add_argument(
        '--output_path',
        type=str,
        help='Path to output JSON file'
    )
    parser.add_argument(
        '--format',
        type=str,
        choices=['array', 'dict', 'grouped'],
        default='array',
        help='Output JSON format (default: array)'
    )
    parser.add_argument(
        '--indent',
        type=int,
        default=2,
        help='JSON indentation spaces (default: 2, use 0 for compact)'
    )
    parser.add_argument(
        '--pretty',
        action='store_true',
        help='Pretty print JSON (equivalent to --indent 2)'
    )
    args = parser.parse_args()

    # Collect files to process
    if os.path.isdir(args.input_path):
        csv_files = []
        for root, dirs, files in os.walk(args.input_path):
            for file in files:
                if file.endswith('.csv'):
                    csv_files.append(os.path.join(root, file))
    else:
        csv_files = [args.input_path]

    if not os.path.exists(args.output_path):
        os.makedirs(args.output_path)

    for input_file_path in csv_files:
        repo_id = os.path.dirname(input_file_path).split('/')[-1]
        output_file_path = os.path.join(args.output_path, "tracto-" + repo_id + "-" + os.path.basename(input_file_path).replace('.csv', '.json'))
        print(f'Processing file {os.path.basename(input_file_path)} from repo id {repo_id} (the final json will be save here: {output_file_path})')
        convert(args, input_file_path, output_file_path)
