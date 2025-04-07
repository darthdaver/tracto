import json
import os
import re
from collections import defaultdict, Counter
import matplotlib.pyplot as plt
from matplotlib.backends.backend_pdf import PdfPages
import numpy as np

# === CONFIGURATION ===
root_data_folder = './_rq1/input_analysis/moderators/analysis'
output_pdf_path = '_rq1/output_analysis/moderators_analyisis/equivalence_analysis.pdf'

# === LOAD DATA ===
def load_all_datapoint_jsons(root_folder):
    all_entries = []
    datapoint_vote_stats = []
    prompt_type_votes = defaultdict(lambda: Counter({'EQUIVALENT': 0, 'NOT EQUIVALENT': 0}))

    for root, _, files in os.walk(root_folder):
        for fname in files:
            if 'datapoints' in fname and fname.endswith('.json'):
                full_path = os.path.join(root, fname)

                # Extract prompt type from path: assume folder name is model_name-N
                match = re.search(r'[^/\\]+-(\d)', full_path)
                prompt_type = int(match.group(1)) if match else None

                try:
                    with open(full_path, 'r') as f:
                        data = json.load(f)
                        if isinstance(data, dict):
                            data = [data]

                        for entry in data:
                            entry['__prompt_type'] = prompt_type
                            all_entries.append(entry)

                            # Count votes per datapoint
                            per_datapoint = Counter()
                            for key, value in entry.items():
                                if ':' in key and value in ('EQUIVALENT', 'NOT EQUIVALENT'):
                                    per_datapoint[value] += 1
                                    if prompt_type:
                                        prompt_type_votes[prompt_type][value] += 1

                            datapoint_vote_stats.append(per_datapoint)

                except Exception as e:
                    print(f"Failed to load {full_path}: {e}")
    return all_entries, datapoint_vote_stats, prompt_type_votes

print("Loading datapoint files...")
data, datapoint_vote_stats, prompt_type_votes = load_all_datapoint_jsons(root_data_folder)
print(f"Loaded {len(data)} datapoints.")

# === AGGREGATE MODEL STATS ===
model_equiv_counts = defaultdict(lambda: Counter({'EQUIVALENT': 0, 'NOT EQUIVALENT': 0}))
model_names = set()

for entry in data:
    for key, value in entry.items():
        if ':' in key and value in ('EQUIVALENT', 'NOT EQUIVALENT'):
            model_equiv_counts[key][value] += 1
            model_names.add(key)

model_names = sorted(model_names)

# === PLOTTING ===
with PdfPages(output_pdf_path) as pdf:

    # 1. Bar chart per model: EQUIVALENT vs NOT
    equivalents = [model_equiv_counts[model]['EQUIVALENT'] for model in model_names]
    not_equivalents = [model_equiv_counts[model]['NOT EQUIVALENT'] for model in model_names]
    x = range(len(model_names))

    plt.figure(figsize=(12, 6))
    plt.bar(x, equivalents, width=0.4, label='EQUIVALENT', color='green', align='center')
    plt.bar(x, not_equivalents, width=0.4, label='NOT EQUIVALENT', color='red', align='edge')
    plt.xticks(x, model_names, rotation=45, ha='right')
    plt.ylabel('Count')
    plt.title('Equivalence Judgments per Model')
    plt.legend()
    plt.tight_layout()
    pdf.savefig()
    plt.close()

    # 2. Pie chart per model
    for model in model_names:
        labels = ['EQUIVALENT', 'NOT EQUIVALENT']
        sizes = [model_equiv_counts[model][label] for label in labels]
        colors = ['green', 'red']
        explode = (0.1, 0)

        plt.figure(figsize=(6, 6))
        plt.pie(sizes, explode=explode, labels=labels, colors=colors,
                autopct='%1.1f%%', shadow=True, startangle=140)
        plt.title(f'Equivalence Distribution for {model}')
        pdf.savefig()
        plt.close()

    # 3. Distribution per datapoint: how many models said EQUIVALENT or NOT
    datapoint_eq = [d['EQUIVALENT'] for d in datapoint_vote_stats]
    datapoint_neq = [d['NOT EQUIVALENT'] for d in datapoint_vote_stats]

    plt.figure(figsize=(10, 5))
    plt.hist(datapoint_eq, bins=range(0, max(datapoint_eq)+2), alpha=0.6, label='EQUIVALENT', color='green')
    plt.hist(datapoint_neq, bins=range(0, max(datapoint_neq)+2), alpha=0.6, label='NOT EQUIVALENT', color='red')
    plt.xlabel('Number of Votes per Datapoint')
    plt.ylabel('Datapoint Count')
    plt.title('Vote Distribution Across Datapoints')
    plt.legend()
    plt.tight_layout()
    pdf.savefig()
    plt.close()

    # 4. Prompt type statistics
    for prompt_type, counts in sorted(prompt_type_votes.items()):
        labels = ['EQUIVALENT', 'NOT EQUIVALENT']
        sizes = [counts['EQUIVALENT'], counts['NOT EQUIVALENT']]
        colors = ['green', 'red']

        plt.figure(figsize=(6, 6))
        plt.pie(sizes, labels=labels, colors=colors, autopct='%1.1f%%', startangle=90)
        plt.title(f'Prompt Type {prompt_type}: Equivalence Vote Distribution')
        pdf.savefig()
        plt.close()

    # 5. Bar plot: Prompt types comparison
    prompt_types = sorted(prompt_type_votes.keys())
    equivs = [prompt_type_votes[t]['EQUIVALENT'] for t in prompt_types]
    not_equivs = [prompt_type_votes[t]['NOT EQUIVALENT'] for t in prompt_types]

    x = range(len(prompt_types))
    plt.figure(figsize=(8, 5))
    plt.bar(x, equivs, width=0.4, label='EQUIVALENT', color='green', align='center')
    plt.bar(x, not_equivs, width=0.4, label='NOT EQUIVALENT', color='red', align='edge')
    plt.xticks(x, [f'Prompt {t}' for t in prompt_types])
    plt.ylabel('Vote Count')
    plt.title('Equivalence Votes by Prompt Type')
    plt.legend()
    plt.tight_layout()
    pdf.savefig()
    plt.close()

    # 6. Agreement when match == true
    match_equiv_counts = []
    for entry in data:
        if entry.get("match") == True:
            count = sum(1 for key, val in entry.items() if ':' in key and val == 'EQUIVALENT')
            match_equiv_counts.append(count)

    plt.figure(figsize=(10, 5))
    plt.hist(match_equiv_counts, bins=range(0, max(match_equiv_counts)+2), color='blue', alpha=0.7)
    plt.xlabel('Number of Models Saying EQUIVALENT')
    plt.ylabel('Count of Datapoints')
    plt.title('Model Agreement (EQUIVALENT) When is EXACT MATCH')
    plt.grid(True)
    pdf.savefig()
    plt.close()

    # 7. Statistical metrics & threshold analysis
    equiv_per_datapoint = [sum(1 for k, v in entry.items() if ':' in k and v == 'EQUIVALENT') for entry in data]
    mean_val = np.mean(equiv_per_datapoint)
    median_val = np.median(equiv_per_datapoint)
    std_dev = np.std(equiv_per_datapoint)

    plt.figure(figsize=(10, 6))
    plt.hist(equiv_per_datapoint, bins=range(0, max(equiv_per_datapoint)+2), alpha=0.75, color='purple')
    plt.axvline(mean_val, color='green', linestyle='dashed', linewidth=2, label=f'Mean = {mean_val:.2f}')
    plt.axvline(median_val, color='orange', linestyle='dashed', linewidth=2, label=f'Median = {median_val:.2f}')
    plt.axvline(mean_val + std_dev, color='red', linestyle='dotted', linewidth=2, label=f'Mean + 1σ = {mean_val + std_dev:.2f}')
    plt.xlabel('Number of Models Voting EQUIVALENT')
    plt.ylabel('Datapoint Count')
    plt.title('Distribution of EQUIVALENT Votes per Datapoint\n(Use This for Threshold Decisions)')
    plt.legend()
    plt.grid(True)
    pdf.savefig()
    plt.close()

    # 8. Full agreement vs disagreement
    full_agree_equiv = 0
    full_agree_nonequiv = 0
    partial_agree = 0

    for entry in data:
        votes = [v for k, v in entry.items() if ':' in k and v in ('EQUIVALENT', 'NOT EQUIVALENT')]
        if len(set(votes)) == 1:
            if votes[0] == 'EQUIVALENT':
                full_agree_equiv += 1
            else:
                full_agree_nonequiv += 1
        else:
            partial_agree += 1

    labels = ['All EQUIVALENT', 'All NOT EQUIVALENT', 'Disagreement']
    values = [full_agree_equiv, full_agree_nonequiv, partial_agree]
    colors = ['green', 'red', 'gray']

    plt.figure(figsize=(8, 5))
    plt.bar(labels, values, color=colors)
    plt.ylabel('Number of Datapoints')
    plt.title('Full Agreement vs Disagreement Among Models')
    for i, v in enumerate(values):
        plt.text(i, v + 1, str(v), ha='center')
    plt.tight_layout()
    pdf.savefig()
    plt.close()


    # 9. Prompt accuracy when match == True (on EQUIVALENT)
    prompt_match_equiv = defaultdict(int)
    prompt_match_total = defaultdict(int)

    for entry in data:
        if entry.get("match") == True and '__prompt_type' in entry:
            prompt_type = entry['__prompt_type']
            num_equiv = sum(1 for k, v in entry.items() if ':' in k and v == 'EQUIVALENT')
            total_votes = sum(1 for k, v in entry.items() if ':' in k and v in ('EQUIVALENT', 'NOT EQUIVALENT'))

            if num_equiv >= 3:  # majority voted EQUIVALENT
                prompt_match_equiv[prompt_type] += 1
            prompt_match_total[prompt_type] += 1

    types = sorted(prompt_match_total.keys())
    correct = [prompt_match_equiv[t] for t in types]
    total = [prompt_match_total[t] for t in types]
    accuracy = [correct[i] / total[i] if total[i] > 0 else 0 for i in range(len(types))]

    plt.figure(figsize=(8, 5))
    plt.bar([f'Prompt {t}' for t in types], accuracy, color='dodgerblue')
    plt.ylim(0, 1.05)
    plt.ylabel('Accuracy (Majority EQUIVALENT when Match=True)')
    plt.title('Prompt Effectiveness on EQUIVALENT (Match=True)')
    for i, acc in enumerate(accuracy):
        plt.text(i, acc + 0.02, f'{acc:.2f}', ha='center')
    plt.tight_layout()
    pdf.savefig()
    plt.close()

print(f"\n✅ Analysis complete. Results saved to: {output_pdf_path}")