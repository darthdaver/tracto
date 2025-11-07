#!/usr/bin/env bash

set -Eeuo pipefail

GPU_ID=${1-0}
target_config=${2-1}

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${current_dir}/utils/model_variables.sh"

export TOKENIZERS_PARALLELISM
export HF_DATASETS_DISABLE_PARALLELISM

# FORCE SPECIFIC GPU
export CUDA_VISIBLE_DEVICES=${GPU_ID}

echo "Running on GPU ${GPU_ID}"

LOCAL_TOKENIZED="${OUTPUT_PROMPT_DIR}/${target_config}/tokens/local_nvme_smoke"
LOCAL_TOKENIZED_PROMPT_BINARY="$(dirname "${LOCAL_TOKENIZED}/prompt-tokenized-smoke-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}")"
mkdir -p "${LOCAL_TOKENIZED_PROMPT_BINARY}"
rsync -a --info=progress2 "${OUTPUT_PROMPT_DIR}/${target_config}/tokens/prompt-tokenized-smoke-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}" "$LOCAL_TOKENIZED_PROMPT_BINARY/"

# Use GPU-specific output directory
OUTPUT_DIR_GPU="${OUTPUT_FINE_TUNING_DIR}_smoke_gpu${GPU_ID}"
mkdir -p "${OUTPUT_DIR_GPU}"

"$PY_ENV" "${NEURAL_DIR}/finetune_json.py" \
  --model_name_or_path "${MODEL_NAME}" \
  --model_revision "${MODEL_REVISION}" \
  --use_flash_attention False \
  --tokenized_dir "${LOCAL_TOKENIZED}/prompt-tokenized-smoke-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}" \
  --output_dir "${OUTPUT_FINE_TUNING_DIR}/smoke" \
  --model_max_length 8192 \
  --per_device_train_batch_size 2 \
  --per_device_eval_batch_size 2 \
  --gradient_accumulation_steps 1 \
  --num_train_epochs 1 \
  --eval_strategy steps --eval_steps 300 \
  --save_strategy steps --save_steps 300 --save_total_limit 1 \
  --bf16 True --fp16 False \
  --gradient_checkpointing True \
  --optim adamw_torch --learning_rate 1e-4 \
  --dataloader_persistent_workers False \
  --dataloader_pin_memory False \
  --dataloader_drop_last \
  --logging_strategy steps --logging_steps 1 \
  --prediction_loss_only True \
  --load_best_model_at_end True \
  --metric_for_best_model eval_loss --greater_is_better False\
  --dataloader_num_workers 2