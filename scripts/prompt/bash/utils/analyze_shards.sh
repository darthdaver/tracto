
# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"
source "${NEURAL_DIR}/bash/utils/model_variables.sh"


python /Users/davidemolinelli/Downloads/tracto/scripts/prompt/utils/analyze_shards.py \
    --shard_dir /Users/davidemolinelli/Downloads/tracto/scripts/prompt/output/1/prompt-binary-Qwen/Qwen2.5-Coder-1.5B-csv-4096 \
    --split train \
    --shard_idx 0 \
    --start_sample 0 \
    --num_samples 20 \
    --tokenizer Qwen/Qwen2.5-Coder-1.5B