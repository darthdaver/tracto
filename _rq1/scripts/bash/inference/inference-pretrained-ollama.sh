# The script run the inference on the models required to replicate RQ1

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../utils/global_variables.sh"

# Get parameters
query_type=$1

if [ ! -d "${RQ1_DATASET_DIR}" ]; then
    curl -o "${ROOT_DIR}/rq1-dataset.zip" "${RQ1_MOTIVATIONAL_PROJECT_EXAMPLE_LINK}"
    unzip "${ROOT_DIR}/rq1-dataset.zip" -d "${ROOT_DIR}"
    rm "${ROOT_DIR}/rq1-dataset.zip"
fi

while IFS=, read -r ol_model hf_tokenizer num_ctx model_type; do
  for i in {1..4}; do
    echo "Running inference with ${ol_model} on query template ${i}"
    "${PY_BIN}" "${PY_INFERENCE_DIR}/infer-pretrained-ollama.py" \
        --model_name_or_path "${ol_model}" \
        --model_type "${model_type}" \
        --tokenizer_name "${hf_tokenizer}" \
        --query_path "${ROOT_DIR}/scripts/resources/queries/pretrained/${query_type}/${i}/query-template.txt" \
        --dataset_path "${RQ1_DATASET_DIR}/${i}" \
        --output_path "${ROOT_DIR}/output/inference/${ol_model}-${i}" \
        --src_col "src" \
        --tgt_col "tgt" \
        --num_ctx "${num_ctx}" \
        --ram_saving true
  done
done < "$RQ1_MODELS_LIST"
