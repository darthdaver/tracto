# The script run the inference of the selected pretrained model on a given dataset.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../utils/global_variables.sh"

python3 "${PY_INFERENCE_DIR}/infer-pretrained.py" \
    --model_name_or_path "Salesforce/codet5-small" \
    --tokenizer_name "Salesforce/codet5-small" \
    --path "/Users/davidemolinelli/Documents/phd/repositories/tracto/neural-module/llm-oracles-dataset/oracles-datapoints-22256861-0.csv" \
    --split "test" \
    --src_col "src" \
    --tgt_col "tgt" \
    --max_src_len 512 \
    --max_tgt_len 512 \
    --batch_size 2 \
    --output_path "${ROOT_DIR}/output/inference/inference.txt" \
    --num_beams 1