#!/usr/bin/env bash
set -euo pipefail

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${current_dir}/utils/model_variables.sh"

# Debug-friendly defaults
export CUDA_VISIBLE_DEVICES=${CUDA_VISIBLE_DEVICES:-0,1}
export NCCL_ASYNC_ERROR_HANDLING=1
export TORCH_DISTRIBUTED_DEBUG=DETAIL
export TOKENIZERS_PARALLELISM=false
export HF_DATASETS_DISABLE_PARALLELISM=1

# Optional: enable NCCL flight recorder for better traces if there’s a hang
export TORCH_NCCL_TRACE_BUFFER_SIZE=1048576

target_config=${1}

LOCAL_TOKENIZED="${OUTPUT_PROMPT_DIR}/${target_config}/local_nvme"
mkdir -p "$LOCAL_TOKENIZED"
rsync -a --info=progress2 "${OUTPUT_PROMPT_DIR}/${target_config}/prompt-binary" "$LOCAL_TOKENIZED/"

python -m torch.distributed.run --nproc-per-node=2 finetuning-speed.py \
  --model_name_or_path Qwen/Qwen2.5-Coder-7B-Instruct \
  --use_flash_attention False \
  --tokenized_dir "${LOCAL_TOKENIZED}/prompt-binary" \
  --output_dir "${OUTPUT_FINE_TUNING_DIR}/smoke" \
  --model_max_length 512 \
  --per_device_train_batch_size 2 \
  --per_device_eval_batch_size 2 \
  --gradient_accumulation_steps 1 \
  --num_train_epochs 1 \
  --eval_strategy steps --eval_steps 5 \
  --save_strategy steps --save_steps 5 --save_total_limit 1 \
  --bf16 True --fp16 False \
  --gradient_checkpointing True \
  --optim adamw_torch --learning_rate 1e-4 \
  --dataloader_num_workers 0 \
  --dataloader_persistent_workers False \
  --dataloader_pin_memory False \
  --dataloader_drop_last \
  --logging_strategy steps --logging_steps 1 \
  --prediction_loss_only True \
  --load_best_model_at_end True \
  --metric_for_best_model eval_loss --greater_is_better False\
  --dataloader_num_workers 2