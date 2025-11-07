#!/bin/bash

# Single-GPU fine-tuning script for full oracle prediction
# Usage: ./finetune_oracle_single_gpu.sh [GPU_ID] [target_config]
# Example: ./finetune_oracle_single_gpu.sh 0 1

set -Eeuo pipefail

GPU_ID=${1-0}
target_config=${2-1}

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")

# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${current_dir}/utils/model_variables.sh"

export WANDB_DISABLED=true
export WANDB_MODE=offline
export TOKENIZERS_PARALLELISM
export HF_DATASETS_DISABLE_PARALLELISM

# FORCE SPECIFIC GPU
export CUDA_VISIBLE_DEVICES=${GPU_ID}

echo "Running on GPU ${GPU_ID}"
echo "Config: ${target_config}"

# Setup local paths
LOCAL_TOKENIZED="${OUTPUT_PROMPT_DIR}/${target_config}/oracles/local_nvme"
LOCAL_TOKENIZED_PROMPT_DIR="$(dirname "${LOCAL_TOKENIZED}/prompt-tokenized-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}")"

mkdir -p "${LOCAL_TOKENIZED_PROMPT_DIR}"

echo "Copying tokenized data to local storage..."
rsync -a --info=progress2 \
    "${OUTPUT_PROMPT_DIR}/${target_config}/oracles/prompt-tokenized-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}" \
    "${LOCAL_TOKENIZED_PROMPT_DIR}/"

# Use GPU-specific output directory
OUTPUT_DIR_GPU="${OUTPUT_DIR}/oracles/finetuning_gpu${GPU_ID}"
mkdir -p "${OUTPUT_DIR_GPU}"

echo "Starting fine-tuning on GPU ${GPU_ID}..."
echo "Output directory: ${OUTPUT_DIR_GPU}"

# Run without distributed launcher (single GPU)
"$PY_ENV" "${NEURAL_DIR}/finetune_oracles_json.py" \
    --model_name_or_path "${MODEL_NAME}" \
    --model_revision "${MODEL_REVISION}" \
    --use_flash_attention False \
    --tokenized_dir "${LOCAL_TOKENIZED}/prompt-tokenized-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}" \
    --output_dir "${OUTPUT_DIR_GPU}" \
    --model_max_length ${MAX_LENGTH} \
    --per_device_train_batch_size 2 \
    --per_device_eval_batch_size 2 \
    --gradient_accumulation_steps 8 \
    --bf16 True \
    --fp16 False \
    --gradient_checkpointing True \
    --group_by_length False \
    --dataloader_num_workers 8 \
    --dataloader_persistent_workers True \
    --dataloader_pin_memory True \
    --optim adamw_torch \
    --learning_rate 2e-4 \
    --lr_scheduler_type cosine \
    --warmup_ratio 0.03 \
    --weight_decay 0.0 \
    --max_grad_norm 0.3 \
    --use_peft True \
    --num_train_epochs 3 \
    --eval_strategy steps --eval_steps 3500 \
    --save_strategy steps --save_steps 3500 --save_total_limit 3 \
    --prediction_loss_only True \
    --eval_accumulation_steps 1 \
    --load_best_model_at_end True \
    --metric_for_best_model eval_loss --greater_is_better False \
    --logging_strategy steps --logging_steps 200 \
    --report_to "none" \
    --ddp_find_unused_parameters False \
    --include_num_input_tokens_seen False \
    --dataloader_drop_last \
    --min_early_stopping_epochs 1 \
    --early_stopping_patience 2 \
    --remove_unused_columns False \
    --debug_samples 5

echo "Fine-tuning completed!"
echo "Model saved to: ${OUTPUT_DIR_GPU}"