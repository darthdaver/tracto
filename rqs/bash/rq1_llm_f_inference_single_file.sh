#!/bin/bash
# Usage: ./finetune_single_gpu.sh <gpu_id> <config>

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../scripts/utils/bash/global_variables.sh"
source "${current_dir}/../../scripts/neural-module/bash/utils/model_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${UTILS_DIR}/bash/init_sdkman.sh"

export TOKENIZERS_PARALLELISM
export HF_DATASETS_DISABLE_PARALLELISM

mkdir -p "${OUTPUT_RQ1}"

TEST_SET_REPOS_LIST_FILE="${1:-${TEST_SET_REPOS_LIST_FILE}}"
config_num=${2-1}

while IFS=, read -r repo_id repo_name; do
  # Define a log file at the top of the script
  log_file="${OUTPUT_RQ1}/llm-f/logs/${test}/inference_log_${repo_id}.log"
  mkdir -p "$(dirname "$log_file")"
  cd "$ROOT_DIR"
  # Clean repo_name string from undesired white-spaces/line-breaks introduced with the CSV parsing
  repo_name="${repo_name//[$'\t\r\n ']/}"
  echo "==============================" >> "$log_file"
  echo "Repo: $repo_name  ID: $repo_id" >> "$log_file"
  echo "==============================" >> "$log_file"
  "$PY_ENV" "${NEURAL_DIR}/inference_llm_f.py" \
     --tokenizer_name_or_path "${OUTPUT_NEURAL_DIR}/finetuning/llm-f/${TOKENIZER}/${config_num}" \
     --model_name_or_path "${OUTPUT_NEURAL_DIR}/finetuning/llm-f/${MODEL_NAME}/${config_num}" \
     --model_revision 09d9bc5d376b0cfa0100a0694ea7de7232525803 \
     --max_src_length 8192 \
     --max_tgt_length 1024 \
     --oracles_dataset_dir_path "${OUTPUT_SYMBOLIC_DIR}/raw-oracles-dataset-inference/oracles-datapoints-${repo_id}-0.json" \
     --output_path "${OUTPUT_RQ1}/inference/llm-f/" \
     --prompt_config 1
done < "$TEST_SET_REPOS_LIST_FILE"