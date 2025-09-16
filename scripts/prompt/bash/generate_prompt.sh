# The script transforms the raw oracles dataset into the final prompts to the model.
# The output to the script is a list of files containing the inputs to the model, for each datapoint.
# Each file is a csv file containing the two columns: src and tgt. The src column contains the input to the model,
# while the tgt column contains the target that the model should predict during the training phase.
# The input is generated using the E-wash approach, according to the configuration file saved in the resources folder
# (ml-model/src/resources/ewash_config.json).

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"

dataset_type=${1:-"oracles"}
target_dir=${2:-"training"}

OUTPUT_DATASET_DIR=""

if [ "${dataset_type}" == "oracles" ]; then
    OUTPUT_DATASET_DIR="${OUTPUT_ORACLES_DATASET}-${target_dir}"
elif [ "${dataset_type}" == "tokens" ]; then
    echo "Generating LLM oracles input"
    OUTPUT_DATASET_DIR="${OUTPUT_TOKENS_DATASET}-${target_dir}"
fi

for config_path in "${PROMPT_DIR}/resources/config"/*/; do
  config_num=$(basename "${config_path}")
  # Define a log file at the top of the script
  log_file="${OUTPUT_DIR}/logs/prompt_${dataset_type}_config_${config_num}.log"
  mkdir -p "$(dirname "$log_file")"
  echo "================================================================" >> "$log_file"
  echo "Generating prompt for ${dataset_type} dataset with configuration ${config_num}" >> "$log_file"
  echo "================================================================" >> "$log_file"
  # Generate LLM oracles input
  "${PY_ENV}" "${PROMPT_DIR}/prompt.py" \
      --input "${OUTPUT_DATASET_DIR}" \
      --output "${OUTPUT_DIR}/${config_num}" \
      --config "${PROMPT_DIR}/resources/config/${config_num}/ewash_config.json" >> "$log_file"
done