# The script generate the final dataset to perform the fine-tuning.
# The script samples datapoints from each project, proportionally to their dimension (sqrt)
# and tokenize them to avoid overhead during the fine-tuning.
# The script generate the final tokenized dataset for fine-tuning

#-e: exit immediately if any command returns a non-zero status.
#-u: treat unset variables as an error (e.g., using $FOO when it wasn’t set).
#-o pipefail: in a pipeline (a | b | c), the whole pipeline fails if any stage fails (not just the last one).
set -euo pipefail

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
  i=$(basename "${config_path}")
  echo "Processing config: ${i}"
  echo "${OUTPUT_DIR}/${i}/prompt"
  "$PY_ENV" "${PROMPT_DIR}/utils/encode.py" \
    --model_name_or_path "${MODEL_NAME:-Qwen/Qwen2.5-Coder-7B}" \
    --encoding_type "${ENCODING_TYPE}" \
    --data_dir   "${OUTPUT_DIR}/${i}/prompt" \
    --train_csv  "${OUTPUT_DIR}/${i}/split-dataset/train_projects.csv" \
    --val_csv    "${OUTPUT_DIR}/${i}/split-dataset/val_projects.csv" \
    --out_dir    "${OUTPUT_DIR}/${i}/prompt-binary-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}" \
    --train_token_budget 2000000000 \
    --val_token_budget   40000000 \
    --max_length "${MAX_LENGTH}" \
    --bin_allocation sqrt \
    --alpha 0.5 \
    --target_mix "0.33,0.34,0.33" \
    --short_cut_frac 0.33 \
    --med_cut_frac   0.66 \
    --tokens_per_shard 50000000 \
    --seed "${SEED}" \
    --train_drop_long \
    --val_drop_long \
    --wrap
    #--start_id "${START_ID:-<|im_start|>}" \
    #--end_id   "${END_ID:-<|im_end|>}" \
done