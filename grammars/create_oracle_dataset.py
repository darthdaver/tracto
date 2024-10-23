"""
This file contains a script to extract oracles from the TrATTo dataset and generate non-complete oracles for each entry.
"""

import os
import json
import re

# Path to the folder containing the JSON files
folder_path = '../../tratto-oracles-dataset/oracles-dataset'

# Path for the output JSON file
output_file = './grammars/Input/new_oracle_dataset.json'

# This pattern matches Java operators, punctuation, and now also includes identifiers (e.g., variable names)
java_token_pattern = r'(\|\||&&|==|!=|<=|>=|<|>|\(|\)|{|}|;|,|\+|\-|\*|/|%|[a-zA-Z_][a-zA-Z0-9_]*)'

# Function to tokenize a Java oracle
def tokenize_java_oracle(oracle):
    """ Tokenize a Java oracle based on the defined pattern """
    # Split the oracle into tokens based on the defined pattern
    tokens = re.split(java_token_pattern, oracle)
    tokens = [token for token in tokens if token.strip()]  # Remove any empty or whitespace-only tokens
    return tokens

# Function to generate non-complete oracle by truncating at a full token boundary
def generate_non_complete_oracle(oracle):
    """ Generate a non-complete oracle by truncating at a full token boundary """
    tokens = tokenize_java_oracle(oracle)
    halfway_index = len(tokens) // 2

    # Ensure we do not truncate mid-token (especially for variable names or method names)
    non_complete_oracle = ''.join(tokens[:halfway_index]).strip()
    return non_complete_oracle

# Function to extract oracle from a list of JSON elements
def extract_oracle_from_json_list(file_path):
    """ Extract oracles from a list of JSON elements """
    with open(file_path, 'r', encoding='utf-8') as file:
        try:
            data_list = json.load(file)
            extracted_data = []
            # Iterate over each JSON element in the list
            for data in data_list:
                oracle = data.get('oracle', None)
                if oracle and oracle != ";":
                    # Full oracle entry
                    extracted_data.append({'oracle': oracle, 'correct': 'true', 'comment': 'none'})

                    # Non-complete oracle entry
                    non_complete_oracle = generate_non_complete_oracle(oracle)
                    extracted_data.append({'oracle': non_complete_oracle, 'correct': 'false', 'comment': 'none'})

            return extracted_data
        except json.JSONDecodeError:
            print(f"Error decoding JSON from file: {file_path}")
            return []

# List to store the extracted oracles with additional fields
output_data = []

# Loop through each file in the folder
for filename in os.listdir(folder_path):
    if filename.endswith('.json'):  # Make sure it's a JSON file
        file_path = os.path.join(folder_path, filename)
        oracle_entries = extract_oracle_from_json_list(file_path)
        output_data.extend(oracle_entries)

# Write the extracted data to the output JSON file
with open(output_file, 'w', encoding='utf-8') as outfile:
    json.dump(output_data, outfile, indent=4)

print(f"Data has been saved to {output_file} with {len(output_data)} oracles.")
