#!/bin/bash
# Usage: ./finetune_single_gpu.sh <gpu_id> <config>

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../scripts/utils/bash/global_variables.sh"
source "${current_dir}/../../scripts/neural-module/bash/utils/model_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${UTILS_DIR}/bash/init_sdkman.sh"

export TOKENIZERS_PARALLELISM
export HF_DATASETS_DISABLE_PARALLELISM

mkdir -p "${OUTPUT_RQ1}"

config_num=${2-1}

"$PY_ENV" "${NEURAL_DIR}/inference_llm_f.py" \
   --tokenizer_name_or_path "${OUTPUT_NEURAL_DIR}/finetuning/llm-f/${TOKENIZER}/${config_num}" \
   --model_name_or_path "${OUTPUT_NEURAL_DIR}/finetuning/llm-f/${MODEL_NAME}/${config_num}" \
   --model_revision 09d9bc5d376b0cfa0100a0694ea7de7232525803 \
   --max_src_length 8192 \
   --max_tgt_length 1024 \
   --oracles_dataset_dir_path "${OUTPUT_SYMBOLIC_DIR}/raw-oracles-dataset-inference" \
   --output_path "${OUTPUT_RQ1}/inference/llm-f/" \
   --prompt_config 1