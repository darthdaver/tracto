# The script run the inference of the selected pretrained model on a given dataset.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../utils/global_variables.sh"

if [ ! -d "${RQ1_DATASET_DIR}" ]; then
    curl -o "${ROOT_DIR}/rq1-dataset.zip" "${RQ1_MOTIVATIONAL_PROJECT_EXAMPLE_LINK}"
    unzip "${ROOT_DIR}/rq1-dataset.zip" -d "${ROOT_DIR}"
    rm "${ROOT_DIR}/rq1-dataset.zip"
fi

for i in {1..4}; do
  while IFS=, read -r ol_model hf_tokenizer num_ctx; do
    echo "Running inference with ${ol_model} on query template ${i}"
    python3 "${PY_INFERENCE_DIR}/infer-pretrained-ollama.py" \
        --model_name_or_path "${ol_model}" \
        --tokenizer_name "${hf_tokenizer}" \
        --query_path "${ROOT_DIR}/scripts/resources/rq1/queries/pretrained/${i}/query-template.txt" \
        --dataset_path "${RQ1_DATASET_DIR}/${i}" \
        --output_path "${ROOT_DIR}/output/rq1/${ol_model}-${i}" \
        --src_col "src" \
        --tgt_col "tgt" \
        --num_ctx "${num_ctx}"
  done < "$RQ1_MODELS_LIST"
done