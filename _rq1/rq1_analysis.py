# Cmds to run:
#   - python3 -m venv .venv
#   - source .venv/bin/activate
#   - pip3 install ollama
#   - python ./_rq1/rq1_analysis.py

import csv
import json
import re
import os
from collections import defaultdict
import ollama

llms = ['deepseek-r1', 'llama3.1', 'phi4', 'gemma3', 'qwen2.5']

# Define the input folder
input_folder = "./_rq1/input_analysis"
output_path = "./_rq1/output_analysis/output_results.csv"
input_types = ["prefix only", "javadoc + signature", "signature + body", "javadoc + signature + body"]

# Initialize data storage
data_summary = defaultdict(lambda: defaultdict(lambda: {"Perfect Match": 0, "LLM thinks Match":0, "Non-Match": 0, "Manual Evaluation": 0, "Total": 0}))


def llm_assertion_moderator(target, oracle_generated):
    #print(test_code)
    prompt = f"""Given two Java assertions: {target} and {oracle_generated}, determine if they are semantically equivalent, so do not consider escape characters for your judgement. Put your answer between: <keep></keep>. Reply only in one of these two forms <keep>EQUIVALENT</keep> or <keep>NOT EQUIVALENT</keep>  

    For example:
    assertEqual("99", variable); and assertEquals("99", variable); are <keep>EQUIVALENT</keep>
    assertEqual("99", variable); and assertEquals("100", variable); are <keep>NOT EQUIVALENT</keep>
    assertEqual("99", variable); and assertEquals("\\\"99\\\"", variable); are <keep>EQUIVALENT</keep>
    """

    # Check if the model is pulled from ollama

    content = None

    equivalent_count = 0
    
    for llm in llms:
        # Get response from current LLM
        #ollama.pull(llm)

        response = ollama.chat(
            model=llm,
            messages=[{"role": "user", "content": prompt}]
        )
        
        # Extract the content
        content = response['message']['content'].strip()
        
        
        # Use regex to extract whatever is between <keep> and </keep>
        match = re.search(r'<keep>(.*?)</keep>', content, re.DOTALL)
        if match:
            extracted_content = match.group(1).strip()
            print(f"Response from {llm}: {extracted_content}")
            if extracted_content == "EQUIVALENT":
                equivalent_count += 1
        else:
            print(f"Response from {llm}: Unable to extract content")
    
    if equivalent_count >= 3:
        return "EQUIVALENT"
    else:
        return "NOT EQUIVALENT"


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
                    reader = csv.DictReader(infile, quoting=csv.QUOTE_MINIMAL)#, escapechar='\"')
                    for row in reader:
                        data_summary[model_folder][current_input_type]["Total"] += 1

                        target = row['target'].rstrip(';')
                        prediction = row['prediction']#.replace('""', '"')
                        
                        try:
                            
                            oracle_match = re.search(r'\bassert\w+\((.*?)\);', prediction)
                            

                            if oracle_match:
                                oracle = oracle_match.group(0).rstrip(';')

                                print(f"\n\n\n{model_folder}: Comparing {target} and {oracle}")


                                llm_assert_equivalent = llm_assertion_moderator(target, oracle)

                                print(f"LLM thinks {llm_assert_equivalent}")

                                if  llm_assert_equivalent == "EQUIVALENT":
                                    data_summary[model_folder][current_input_type]["LLM thinks Match"]+=1

                                if target == oracle:
                                    data_summary[model_folder][current_input_type]["Perfect Match"] += 1
                                else:
                                    data_summary[model_folder][current_input_type]["Non-Match"] += 1
                            else:
                                data_summary[model_folder][current_input_type]["Manual Evaluation"] += 1
                                
                        except (json.JSONDecodeError, KeyError):
                            data_summary[model_folder][current_input_type]["Manual Evaluation"] += 1
                        break

# Prepare the output data
headers = [
    # First header row - each input type appears once with empty cells for merging
    ["LLM"] + sum([[input_type, "", "", ""] for input_type in input_types], []),
    # Second header row - M/C/U under each input type group
    [""] + ["Perfect Match", "LLM thinks Match", "Manual Evaluation", "Non-Match"] * len(input_types)
]

output_rows = headers

# Add data rows for each model
for model in sorted(data_summary.keys()):
    row = [model]
    for input_type in input_types:
        if input_type in data_summary[model]:
            total = data_summary[model][input_type]["Total"]
            print(f"{model} - {input_type}: {total}")
            if total > 0:
                m_percent = round(data_summary[model][input_type]["Perfect Match"] / total * 100, 1)
                llm_percent = round(data_summary[model][input_type]["LLM thinks Match"] / total * 100, 1)
                c_percent = round(data_summary[model][input_type]["Manual Evaluation"] / total * 100, 1)
                u_percent = round(data_summary[model][input_type]["Non-Match"] / total * 100, 1)
                row.extend([f"{m_percent}%", f"{c_percent}%", f"{u_percent}%"])
            else:
                row.extend(["0%", "0%", "0%", "0%"])
        else:
            row.extend(["N/A", "N/A", "N/A", "N/A"])
    output_rows.append(row)

# Write the output CSV file
with open(output_path, 'w', newline='', encoding='utf-8') as outfile:
    writer = csv.writer(outfile)
    writer.writerows(output_rows)

print(f"Processing complete. Results saved to {output_path}.")