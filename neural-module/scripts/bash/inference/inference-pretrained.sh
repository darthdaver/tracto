# The script run the inference on the models required to replicate RQ1

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
  while IFS=, read -r _ hf_model num_ctx; do
    # LLama3.1 8B is not available without authorization and deepseek r1 is not available in hf
    if [ "$hf_model" == "meta-llama/Llama-3.1-8B" ] || [ "$animal" == "unavailable" ]; then
      continue
    fi
    echo "Running inference with ${hf_model} on query template ${i}"
    python3 "${PY_INFERENCE_DIR}/infer-pretrained.py" \
        --model_name_or_path "${hf_model}" \
        --tokenizer_name "${hf_model}" \
        --query_path "${SCRIPTS_RESOURCES_DIR}/rq1/queries/pretrained/${i}/query-template.txt" \
        --dataset_path "${RQ1_DATASET_DIR}/${i}/*.csv" \
        --output_path "${ROOT_DIR}/output/rq1/${hf_model}-${i}" \
        --split "test" \
        --src_col "src" \
        --tgt_col "tgt" \
        --max_src_len "${num_ctx}" \
        --max_tgt_len 100 \
        --batch_size 2 \
        --num_beams 1
  done < "$RQ1_MODELS_LIST"
done