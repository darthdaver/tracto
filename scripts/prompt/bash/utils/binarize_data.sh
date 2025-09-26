# The script convert the prompts dataset originally in .csv format to .jsonl format

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"
source "${NEURAL_DIR}/bash/utils/model_variables.sh"

query_path=${1:-$QUERY_STD_PATH}

if [ $# -eq 1 ]; then
    config_paths=("$1")
else
    config_paths=("${PROMPT_DIR}/resources/config/"*/)
fi

for config_path in "${config_paths[@]}"; do
  config_num=$(basename "${config_path}")
  i=$(basename "$config_num")
  "$PY_ENV" "${PROMPT_DIR}/utils/binarize_data.py" \
    -input_path "${OUTPUT_DIR}/${i}/prompt-jsonl" \
    -output_path "${OUTPUT_DIR}/${i}/prompt-binary" \
    -workers 64 \
    -tokenizer_path ${TOKENIZER}
done