# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Vars
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${current_dir}/utils/model_variables.sh"

export TOKENIZERS_PARALLELISM
export HF_DATASETS_DISABLE_PARALLELISM

config_num="${1-1}"
port="${2:-5000}"

"$PY_ENV" "${NEURAL_DIR}/server.py" \
  --port "$port" \
  --tokenizer_name_or_path "${OUTPUT_FINE_TUNING_DIR}/${TOKENIZER_NAME}/${config_num}" \
  --model_name_or_path "${OUTPUT_FINE_TUNING_DIR}/${MODEL_NAME}/${config_num}" \
  --model_revision "${MODEL_REVISION}" \
  --max_src_length 8192 \
  --max_tgt_length 1024