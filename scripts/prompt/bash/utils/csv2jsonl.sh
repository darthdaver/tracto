# The script convert the prompts dataset originally in .csv format to .jsonl format

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"

query_path=${1:-$QUERY_STD_PATH}

for config_path in "${PROMPT_DIR}/resources/config"/*/; do
  config_num=$(basename "${config_path}")
  i=$(basename "$config_num")
  "$PY_ENV" "${PROMPT_DIR}/utils/csv2jsonl.py" "${OUTPUT_DIR}/${i}/prompt" "${OUTPUT_DIR}/${i}/prompt-jsonl" "${query_path}"
done