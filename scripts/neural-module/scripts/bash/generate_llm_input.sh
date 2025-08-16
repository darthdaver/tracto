# The script transforms all the raw data in the oracles/tokens dataset into the final input to the model.
# The output to the script is a list of files containing the inputs to the model, for each datapoint.
# Each file is a csv file containing the two columns: src and tgt. The src column contains the input to the model,
# while the tgt column contains the target that the model should predict during the training phase.
# The input is generated using the E-wash approach, according to the configuration file saved in the resources folder
# (ml-model/src/resources/ewash_config.json).

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../utils/global_variables.sh"

llm_input_type=$1

if [ "${llm_input_type}" == "tokens" ]; then
    echo "Generating LLM tokens input"
    # Generate LLM tokens input
    python "${PY_PREPROCESSING_DIR}/ewash.py" \
        --input "${RAW_TOKENS_DATASET_DIR}" \
        --output "${LLM_TOKENS_DATASET_DIR}"
elif [ "${llm_input_type}" == "oracles" ]; then
    echo "Generating LLM oracles input"
    # Generate LLM oracles input
    python "${PY_PREPROCESSING_DIR}/ewash.py" \
        --input "${RAW_ORACLES_DATASET_DIR}" \
        --output "${LLM_ORACLES_DATASET_DIR}"
else
    echo "Invalid LLM input type provided. Please provide either 'tokens' or 'oracles'."
    exit 1
fi
