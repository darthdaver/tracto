import pandas as pd
import sys
import json
import os

def preprocess_dp(query_template, src_col):
    """
    Preprocess a single datapoint from the dataset.
    Args:
        query_template (`str`):
            The query text to prepend to the source text.
        src_col (`str`):
            The name of the source column in the dataset.
    """
    query = query_template.replace("<QUERY_INPUT>", src_col)
    return query

if __name__ == '__main__':
    """
    Convert a CSV file with 'src' and 'tgt' columns to a JSONL file in ChatML format
    """
    input_path = sys.argv[1]
    output_path = sys.argv[2]
    query_path = sys.argv[3]

    print(f"Input path: {input_path}")
    print(f"Output path: {output_path}")
    print(f"Query path: {query_path}")
    print("Converting CSV to JSONL...")

    # Get query text
    with open(query_path, 'r') as query_file:
        query_template = query_file.read()

    if not os.path.exists(output_path):
        os.makedirs(output_path)

    # Collect files to process
    if os.path.isdir(input_path):
        prompt_dataset_files = []
        for root, dirs, files in os.walk(input_path):
            for file in files:
                if file.endswith('.csv'):
                    prompt_dataset_files.append(os.path.join(root, file))
    else:
        prompt_dataset_files = [input_path]

    # Process each csv file in the original dataset of prompts
    for filename in prompt_dataset_files:
        print(f"Processing file: {filename}")
        df = pd.read_csv(filename)
        #system_prompt = "You are a software tester assistant and you help to generate test oracles, token by token, given a test prefix and additional context information."
        print(f"Output file: {os.path.join(output_path, os.path.basename(filename.replace('.csv', '.jsonl')))}")
        with open(os.path.join(output_path, os.path.basename(filename.replace('.csv', '.jsonl'))), 'w') as f_out:
            dp_counter = 0
            for _, row in df.iterrows():
                print(f"Processing datapoint {dp_counter}/{len(df)}", end='\r')
                query = preprocess_dp(query_template, row['src'])
                messages = [
                    #{"role": "system", "content": system_prompt},
                    {"role": "user", "content": query},
                    {"role": "assistant", "content": row['tgt']}
                ]
                json.dump({"messages": messages, "format": "chatml"}, f_out)
                f_out.write("\n")
                dp_counter += 1