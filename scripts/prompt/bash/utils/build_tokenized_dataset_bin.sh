# The script generate the final dataset to perform the fine-tuning.
# The script samples datapoints from each project, proportionally to their dimension (sqrt)
# and tokenize them to avoid overhead during the fine-tuning.
# The script generate the final tokenized dataset for fine-tuning

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"
source "${NEURAL_DIR}/bash/utils/model_variables.sh"

if [ $# -eq 1 ]; then
    config_paths=("$1")
else
    config_paths=("${PROMPT_DIR}/resources/config/"*/)
fi

for config_path in "${config_paths[@]}"; do
  config_num=$(basename "${config_path}")
  i=$(basename "$config_num")
  python "${PROMPT_DIR}/utils/build_tokenized_dataset_bin.py" \
      --model_name "${MODEL_NAME}" \
      --model_revision "${MODEL_REVISION}" \
      --data_dir   "${OUTPUT_DIR}/${i}/prompt-jsonl" \
      --train_csv  "${OUTPUT_DIR}/${i}/split-dataset/train_projects.csv" \
      --val_csv    "${OUTPUT_DIR}/${i}/split-dataset/val_projects.csv" \
      --out_dir    "${OUTPUT_DIR}/${i}/prompt-binary" \
      --train_token_budget 2000000000 \
      --val_token_budget   40000000 \
      --max_length 8192 \
      --bin_allocation sqrt \
      --alpha 0.5 \
      --target_mix 0.4,0.4,0.2 \
      --tokens_per_shard 50000000 \
      --train_drop_long
done