# The script transforms all the raw data in the oracles/tokens dataset into the final input to the model.
# The output to the script is a list of files containing the inputs to the model, for each datapoint.
# Each file is a csv file containing the two columns: src and tgt. The src column contains the input to the model,
# while the tgt column contains the target that the model should predict during the training phase.
# The input is generated using the E-wash approach, according to the configuration file saved in the resources folder
# (ml-model/src/resources/ewash_config.json).

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../../utils/global_variables.sh"

bash "${BASH_PREPROCESSING_DIR}/generate_ml_input.sh" \
    --input "${RAW_ORACLES_DATASET_DIR}" \
    --output "${ML_ORACLES_DATASET_DIR}"
