#!/bin/bash
# Usage: ./finetune_single_gpu.sh <gpu_id> <config>

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

LOCAL_TOKENIZED="${OUTPUT_PROMPT_DIR}/${target_config}/local_nvme"
LOCAL_TOKENIZED_PROMPT_BINARY="$(dirname "${LOCAL_TOKENIZED}/prompt-binary-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}-tmp")"
mkdir -p "${LOCAL_TOKENIZED_PROMPT_BINARY}"
rsync -a --info=progress2 "${OUTPUT_PROMPT_DIR}/${target_config}/prompt-binary-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}-tmp" "$LOCAL_TOKENIZED_PROMPT_BINARY/"

# Use GPU-specific output directory
OUTPUT_DIR_GPU="${OUTPUT_FINE_TUNING_DIR}_gpu${GPU_ID}"
mkdir -p "${OUTPUT_DIR_GPU}"

# Run without distributed launcher
"$PY_ENV" "${NEURAL_DIR}/finetune_json.py" \
  --model_name_or_path "${MODEL_NAME}" \
  --model_revision "${MODEL_REVISION}" \
  --use_flash_attention False \
  --tokenized_dir "${LOCAL_TOKENIZED}/prompt-binary-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}-tmp" \
  --output_dir "${OUTPUT_DIR_GPU}" \
  --model_max_length ${MAX_LENGTH} \
  --per_device_train_batch_size 4 \
  --per_device_eval_batch_size 4 \
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
  --eval_strategy steps --eval_steps 50 \
  --save_strategy steps --save_steps 50 --save_total_limit 3 \
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
  --remove_unused_columns False
