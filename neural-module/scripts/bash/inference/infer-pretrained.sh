# The script run the inference of the selected pretrained model on a given dataset.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../utils/global_variables.sh"

python3 "${PY_INFERENCE_DIR}/infer-pretrained.py" \
    --model_name_or_path "codellama/CodeLlama-7b-hf" \
    --tokenizer_name "codellama/CodeLlama-7b-hf" \
    --query_path "${ROOT_DIR}/scripts/resources/rq1/queries/pretrained/1/query-template.txt" \
    --dataset_path "${ROOT_DIR}/llm-oracles-dataset/1/*.csv" \
    --output_path "${ROOT_DIR}/output/inference/inference.txt" \
    --split "test" \
    --src_col "src" \
    --tgt_col "tgt" \
    --max_src_len 32000 \
    --max_tgt_len 32000 \
    --batch_size 2 \
    --num_beams 1