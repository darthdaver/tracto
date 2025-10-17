# The script performs the finetuning of the model. The name of the model and the tokenizer are retrieved from the
# `model_variables.sh` script. The finetuning uses an early stopping strategy: after 2 epochs with no improvement
# the finetuning ends and the best model is saved in the output directory. The finetuning performs an evaluation
# at the end of any epoch. The finetuning ends after 5 epochs, in any case.

set -Eeuo pipefail

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${current_dir}/utils/model_variables.sh"

export TOKENIZERS_PARALLELISM
export HF_DATASETS_DISABLE_PARALLELISM

target_config=${1}

LOCAL_TOKENIZED="${OUTPUT_PROMPT_DIR}/${target_config}/local_nvme"
LOCAL_TOKENIZED_PROMPT_BINARY="$(dirname "${LOCAL_TOKENIZED}/prompt-binary-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}")"
mkdir -p "${LOCAL_TOKENIZED_PROMPT_BINARY}"
rsync -a --info=progress2 "${OUTPUT_PROMPT_DIR}/${target_config}/prompt-binary-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}" "$LOCAL_TOKENIZED_PROMPT_BINARY/"

# NOTE
# - --tokenized_dir must point to the directory produced by the builder,
#   containing: train.*.{bin,idx}, train.meta.json, val.*.{bin,idx}, val.meta.json
# - We evaluate/save at end of each epoch so early stopping can't trigger before epoch 1.

"$PY_ENV" -m torch.distributed.run --nproc-per-node=2 "${NEURAL_DIR}/finetuning.py" \
  --model_name_or_path "${MODEL_NAME}" \
  --model_revision "${MODEL_REVISION}" \
  --use_flash_attention False \
  --tokenized_dir "${LOCAL_TOKENIZED}/prompt-binary-${MODEL_NAME}-${ENCODING_TYPE}-${MAX_LENGTH}" \
  --output_dir "${OUTPUT_FINE_TUNING_DIR}" \
  --model_max_length ${MAX_LENGTH} \
  --per_device_train_batch_size 1 \
  --per_device_eval_batch_size 1 \
  --gradient_accumulation_steps 16 \
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
  --remove_unused_columns False