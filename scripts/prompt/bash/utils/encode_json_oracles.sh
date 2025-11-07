#!/bin/bash
# Script to generate the final tokenized dataset for full oracle prediction fine-tuning.

# The script samples datapoints from each project, proportionally to their dimension (sqrt)
# and tokenizes them to avoid overhead during the fine-tuning.

# Exit immediately if any command returns a non-zero status.
# Treat unset variables as an error.
# In a pipeline (a | b | c), the whole pipeline fails if any stage fails.
set -euo pipefail

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[0]}")")

# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"
source "${NEURAL_DIR}/bash/utils/model_variables.sh"

# Parse command line arguments
if [ $# -eq 1 ]; then
    config_paths=("$1")
else
    config_paths=("${PROMPT_DIR}/resources/config/"*/)
fi

dataset_type=${1-"tokens"}

# Process each configuration
for config_path in "${config_paths[@]}"; do
    i=$(basename "${config_path}")
    echo "Processing config: ${i}"
    echo "Data directory: ${OUTPUT_DIR}/${i}/prompt"

    # Run the simplified encoding script
    "$PY_ENV" "${PROMPT_DIR}/utils/encode_json_oracles.py" \
        --model_name_or_path "${MODEL_NAME:-Qwen/Qwen2.5-Coder-7B}" \
        --data_dir "${OUTPUT_DIR}/${i}/${dataset_type}/prompt" \
        --train_csv "${OUTPUT_DIR}/${i}/split-dataset/train_projects.csv" \
        --val_csv "${OUTPUT_DIR}/${i}/split-dataset/val_projects.csv" \
        --out_dir "${OUTPUT_DIR}/${i}/${dataset_type}/prompt-tokenized-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}" \
        --train_token_budget 2000000000 \
        --val_token_budget 40000000 \
        --max_length "${MAX_LENGTH}" \
        --bin_allocation sqrt \
        --alpha 0.5 \
        --target_mix "0.4,0.4,0.2" \
        --short_cut_frac 0.33 \
        --med_cut_frac 0.66 \
        --samples_per_json_file 1000 \
        --seed "${SEED}" \
        --train_drop_long \
        --val_drop_long

    echo "Completed processing config: ${i}"
    echo "---"
done

echo "All configurations processed successfully!"