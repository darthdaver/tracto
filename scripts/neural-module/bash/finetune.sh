# The script performs the finetuning of the model. The name of the model and the tokenizer are retrieved from the
# `model_variables.sh` script. The finetuning uses an early stopping strategy: after 2 epochs with no improvement
# the finetuning ends and the best model is saved in the output directory. The finetuning performs an evaluation
# at the end of any epoch. The finetuning ends after 5 epochs, in any case.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${current_dir}/utils/model_variables.sh"

# "$PY_ENV" "${NEURAL_DIR}/finetuning.py" \
torchrun --nproc-per-node=2 "${NEURAL_DIR}/finetuning.py" \
    --model_name_or_path "Qwen/Qwen2.5-Coder-7B-Instruct" \
    --use_flash_attention "False" \
    --train_file "${OUTPUT_SPLIT_DIR}/train_projects.csv" \
    --eval_file "${OUTPUT_SPLIT_DIR}/val_projects.csv" \
    --data_dir "${OUTPUT_PROMPT_BINARY_DATASET}" \
    --output_dir "${OUTPUT_FINE_TUNING_DIR}" \
    --model_max_length 32768 \
    --num_train_epochs 5 \
    --truncate_source True \
    --use_peft True \
    --per_device_train_batch_size 4 \
    --per_device_eval_batch_size 4 \
    --gradient_accumulation_steps 8 \
    --fp16 True \
    --evaluation_strategy "epoch" \
    --eval_steps 1000 \
    --save_strategy "epoch" \
    --save_steps 1000 \
    --save_total_limit 1 \
    --load_best_model_at_end True \
    --metric_for_best_model "eval_loss" \
    --greater_is_better False \
    --logging_strategy "steps" \
    --logging_steps 10000