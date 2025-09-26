# The script computes statistics related to the prompts generated, such as the maximum and minimum value of the prompts,
# the average value, the percentiles, the quantiles, etc.
# The stats are generated per project and overall.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${NEURAL_DIR}/bash/utils/model_variables.sh"

if [ $# -eq 1 ]; then
    config_paths=("$1")
else
    config_paths=("${PROMPT_DIR}/resources/config/"*/)
fi

for config_path in "${config_paths[@]}"; do
  config_num=$(basename "${config_path}")
  i=$(basename "$config_num")
  ${PY_ENV} "${PROMPT_DIR}/utils/prompt_stats.py" \
      "${OUTPUT_DIR}/${i}/prompt" \
      "${OUTPUT_DIR}/${i}/stats" \
      --model "${MODEL_NAME}" \
      --batch_size 512
done
