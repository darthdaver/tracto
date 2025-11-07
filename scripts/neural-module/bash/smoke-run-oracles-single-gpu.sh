#!/usr/bin/env bash

# Smoke test for single-GPU fine-tuning (quick validation)
# Usage: ./smoke_run_oracle_single_gpu.sh [GPU_ID] [target_config]
# Example: ./smoke_run_oracle_single_gpu.sh 0 1

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

echo "====================================="
echo "SMOKE TEST - Single GPU Training"
echo "====================================="
echo "GPU: ${GPU_ID}"
echo "Config: ${target_config}"
echo ""

# Setup local paths for smoke test
LOCAL_TOKENIZED="${OUTPUT_PROMPT_DIR}/${target_config}/local_nvme_smoke"
LOCAL_TOKENIZED_PROMPT_DIR="$(dirname "${LOCAL_TOKENIZED}/prompt-tokenized-smoke-oracles-${MODEL_NAME}-${MAX_LENGTH}")"

mkdir -p "${LOCAL_TOKENIZED_PROMPT_DIR}"

echo "Copying smoke test data to local storage..."
rsync -a --info=progress2 \
    "${OUTPUT_PROMPT_DIR}/${target_config}/prompt-tokenized-smoke-oracles-${MODEL_NAME}-${MAX_LENGTH}" \
    "${LOCAL_TOKENIZED_PROMPT_DIR}/"

# Use GPU-specific output directory for smoke test
OUTPUT_DIR_GPU="${OUTPUT_FINE_TUNING_DIR}_smoke_gpu${GPU_ID}"
mkdir -p "${OUTPUT_DIR_GPU}"

echo ""
echo "Starting smoke test on GPU ${GPU_ID}..."
echo "Output directory: ${OUTPUT_DIR_GPU}"
echo ""

# Run smoke test with minimal settings
"$PY_ENV" "${NEURAL_DIR}/finetune_oracles_json.py" \
    --model_name_or_path "${MODEL_NAME}" \
    --model_revision "${MODEL_REVISION}" \
    --use_flash_attention False \
    --tokenized_dir "${LOCAL_TOKENIZED}/prompt-tokenized-smoke-oracles-${MODEL_NAME}-${MAX_LENGTH}" \
    --output_dir "${OUTPUT_DIR_GPU}" \
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
    --metric_for_best_model eval_loss --greater_is_better False \
    --dataloader_num_workers 2 \
    --debug_samples 1

echo ""
echo "====================================="
echo "Smoke test completed successfully!"
echo "====================================="
echo "Model saved to: ${OUTPUT_DIR_GPU}"