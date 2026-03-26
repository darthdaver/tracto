#!/bin/bash
# The script transforms the raw oracles dataset into the final prompts with RAG context integration.
# The output to the script is a list of files containing the inputs to the model, for each datapoint.
# Each file is a csv file containing the two columns: src and tgt. The src column contains the input to the model,
# while the tgt column contains the target that the model should predict during the training phase.
# The input is generated using the E-wash approach with RAG-enhanced context integration.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${current_dir}/utils/local_variables_rag.sh"

dataset_type=${1:-"oracles"}
target_dir=${2:-"training"}
config_num=${3:-"1"}

OUTPUT_DATASET_DIR=""

if [ "${dataset_type}" == "oracles" ]; then
    OUTPUT_DATASET_DIR="${OUTPUT_ORACLES_DATASET}-${target_dir}"
elif [ "${dataset_type}" == "tokens" ]; then
    echo "Generating LLM oracles input"
    OUTPUT_DATASET_DIR="${OUTPUT_TOKENS_DATASET}-${target_dir}"
fi

# Define the output directory
OUTPUT_DIR="${PROMPT_DIR}/output/${config_num}/${dataset_type}"

# Define a log file at the top of the script
log_file="${OUTPUT_DIR}/logs/prompt_rag_${dataset_type}_config_${config_num}.log"
mkdir -p "$(dirname "$log_file")"

echo "================================================================" >> "$log_file"
echo "Generating prompt with RAG for ${dataset_type} dataset" >> "$log_file"
echo "Configuration: ${config_num}" >> "$log_file"
echo "RAG enabled: ${RAG_ENABLED}" >> "$log_file"
echo "RAG config: ${RAG_CONFIG_FILE}" >> "$log_file"
echo "================================================================" >> "$log_file"

# Generate LLM oracles input with RAG context
"${PY_ENV}" "${PROMPT_DIR}/prompt_qwen.py" \
    --input "${OUTPUT_DATASET_DIR}" \
    --output "${OUTPUT_DIR}" \
    --config "${PROMPT_DIR}/resources/config/${config_num}/ewash_config_qwen.json" \
    --rag-config "${RAG_CONFIG_FILE}" \
    --rag-enabled "${RAG_ENABLED}" \
    --rag-max-snippets 5 \
    --rag-threshold 0.7 >> "$log_file"

echo "Prompt generation with RAG completed. Logs saved to: $log_file"
