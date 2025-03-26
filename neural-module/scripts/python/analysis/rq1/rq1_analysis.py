import csv
import json
import re
import os
from collections import defaultdict

# Define the input folder
input_folder = "/Users/luca/Documents/USI/Davide/tracto/neural-module/scripts/python/analysis/rq1/input_analysis"
folders = ["qwen2.5-coder:7b-1", "qwen2.5-coder:7b-2", "qwen2.5-coder:7b-3", "qwen2.5-coder:7b-4"]
input_types = ["prefix only", "javadoc + signature", "signature + body", "javadoc + signature + body"]
common_name = "qwen2.5-coder"

# Initialize data storage
data_summary = defaultdict(lambda: {"Perfect Match": 0, "Non-Match": 0, "Manual Evaluation": 0, "Total": 0})

# Process each folder
for folder in folders:
    folder_path = os.path.join(input_folder, folder)
    
    # Read each CSV file in the folder
    for file in os.listdir(folder_path):
        if file.endswith(".csv"):
            file_path = os.path.join(folder_path, file)
            
            with open(file_path, 'r', newline='', encoding='utf-8') as infile:
                reader = csv.DictReader(infile)
                for row in reader:
                    data_summary[folder]["Total"] += 1
                    target = row['target'].rstrip(';')
                    prediction = row['prediction']
                    
                    try:
                        match = re.search(r'```json\s*(\{.*?\})\s*```', prediction, re.DOTALL)
                        if match:
                            json_str = match.group(1)
                            data = json.loads(json_str)
                            
                            if 'oracle' in data:
                                oracle_match = re.search(r'\b\w+\(.*?\)', data['oracle'])
                                if oracle_match:
                                    oracle = oracle_match.group(0).rstrip(';')
                                    if target == oracle:
                                        data_summary[folder]["Perfect Match"] += 1
                                    else:
                                        data_summary[folder]["Non-Match"] += 1
                                else:
                                    data_summary[folder]["Manual Evaluation"] += 1
                            else:
                                data_summary[folder]["Manual Evaluation"] += 1
                        else:
                            data_summary[folder]["Manual Evaluation"] += 1
                    except (json.JSONDecodeError, KeyError):
                        data_summary[folder]["Manual Evaluation"] += 1

# Prepare the output data
headers = [
    ["LLM"] + [input_types[0]],
    sum([[ "", input_type.replace("prefix only", ""), ""] for input_type in input_types], []),
    [""] + ["Perfect Match", "Manual Evaluation", "Non-Match"] * len(input_types)
]

# Add the data row with percentages
row = [common_name]
for folder in folders:
    total = data_summary[folder]["Total"]
    if total == 0:
        # Handle case where there are no samples
        row.extend(["0%", "0%", "0%"])
    else:
        m_percent = round(data_summary[folder]["Perfect Match"] / total * 100, 1)
        c_percent = round(data_summary[folder]["Manual Evaluation"] / total * 100, 1)
        u_percent = round(data_summary[folder]["Non-Match"] / total * 100, 1)
        row.extend([f"{m_percent}%", f"{c_percent}%", f"{u_percent}%"])

# Combine headers and data
output_rows = headers + [row]

# Write the output CSV file
output_path = "/Users/luca/Documents/USI/Davide/tracto/neural-module/scripts/python/analysis/rq1/output_analysis/output_percentages.csv"
with open(output_path, 'w', newline='', encoding='utf-8') as outfile:
    writer = csv.writer(outfile)
    writer.writerows(output_rows)

print(f"Processing complete. Results saved to {output_path}.")