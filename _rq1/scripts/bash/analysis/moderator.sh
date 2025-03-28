# The script run the inference on the models required to replicate RQ1

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../utils/global_variables.sh"

"${PY_BIN}" "${PY_ANALYSIS_DIR}/moderator.py" \
    --input_path "${ROOT_DIR}/output/inference" \
    --output_path "${ROOT_DIR}/output/analysis" \
    --model_list "${RQ1_MODELS_LIST}" \
    --num_ctx "${num_ctx}"
