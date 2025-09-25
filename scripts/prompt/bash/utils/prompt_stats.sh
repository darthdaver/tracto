# The script computes statistics related to the prompts generated, such as the maximum and minimum value of the prompts,
# the average value, the percentiles, the quantiles, etc.
# The stats are generated per project and overall.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"

for config_path in "${PROMPT_DIR}/resources/config"/*/; do
  config_num=$(basename "${config_path}")
  i=$(basename "$config_num")
  ${PY_ENV} "${PROMPT_DIR}/utils/prompt_stats.py" \
      "${OUTPUT_DIR}/${i}/prompt" \
      "${OUTPUT_DIR}/${i}/stats" \
      --model "Qwen/Qwen2.5-Coder-14B-Instruct" \
      --batch_size 512
done
