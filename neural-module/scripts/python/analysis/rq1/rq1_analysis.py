import csv
import json
import re
import os
from collections import defaultdict

# Define the input folder
input_folder = "/Users/luca/Documents/USI/Davide/tracto/neural-module/scripts/python/analysis/rq1/input_analysis"
input_types = ["prefix only", "javadoc + signature", "signature + body", "javadoc + signature + body"]

# Initialize data storage
data_summary = defaultdict(lambda: defaultdict(lambda: {"Perfect Match": 0, "Non-Match": 0, "Manual Evaluation": 0, "Total": 0}))

# Process each model folder
for model_folder in os.listdir(input_folder):
    model_path = os.path.join(input_folder, model_folder)
    if not os.path.isdir(model_path):
        continue
    
    # Process each variant folder (qwen2.5-coder:7b-1, etc.)
    for variant_folder in os.listdir(model_path):
        variant_path = os.path.join(model_path, variant_folder)
        if not os.path.isdir(variant_path):
            continue
        
        # Determine input type from variant number (-1, -2, etc.)
        try:
            variant_num = int(variant_folder.split("-")[-1])
            current_input_type = input_types[variant_num - 1]
        except (IndexError, ValueError):
            continue
        
        # Read each CSV file in the variant folder
        for file in os.listdir(variant_path):
            if file.endswith(".csv"):
                file_path = os.path.join(variant_path, file)
                
                with open(file_path, 'r', newline='', encoding='utf-8') as infile:
                    reader = csv.DictReader(infile)
                    for row in reader:
                        data_summary[model_folder][current_input_type]["Total"] += 1
                        target = row['target'].rstrip(';')
                        prediction = row['prediction']
                        
                        try:
                            oracle_match = re.search(r'\b\w+\(.*?\)', prediction)
                            if oracle_match:
                                oracle = oracle_match.group(0).rstrip(';')
                                if target == oracle:
                                    data_summary[model_folder][current_input_type]["Perfect Match"] += 1
                                else:
                                    data_summary[model_folder][current_input_type]["Non-Match"] += 1
                            else:
                                data_summary[model_folder][current_input_type]["Manual Evaluation"] += 1
                       
                        except (json.JSONDecodeError, KeyError):
                            data_summary[model_folder][current_input_type]["Manual Evaluation"] += 1

# Prepare the output data
headers = [
    # First header row - each input type appears once with empty cells for merging
    ["LLM"] + sum([[input_type, "", ""] for input_type in input_types], []),
    # Second header row - M/C/U under each input type group
    [""] + ["Perfect Match", "Need Manual Check", "Unmatch"] * len(input_types)
]

output_rows = headers

# Add data rows for each model
for model in sorted(data_summary.keys()):
    row = [model]
    for input_type in input_types:
        if input_type in data_summary[model]:
            total = data_summary[model][input_type]["Total"]
            if total > 0:
                m_percent = round(data_summary[model][input_type]["Perfect Match"] / total * 100, 1)
                c_percent = round(data_summary[model][input_type]["Manual Evaluation"] / total * 100, 1)
                u_percent = round(data_summary[model][input_type]["Non-Match"] / total * 100, 1)
                row.extend([f"{m_percent}%", f"{c_percent}%", f"{u_percent}%"])
            else:
                row.extend(["0%", "0%", "0%"])
        else:
            row.extend(["N/A", "N/A", "N/A"])
    output_rows.append(row)

# Write the output CSV file
output_path = "/Users/luca/Documents/USI/Davide/tracto/neural-module/scripts/python/analysis/rq1/output_analysis/output_results.csv"
with open(output_path, 'w', newline='', encoding='utf-8') as outfile:
    writer = csv.writer(outfile)
    writer.writerows(output_rows)

print(f"Processing complete. Results saved to {output_path}.")