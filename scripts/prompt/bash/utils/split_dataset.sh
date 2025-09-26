# The script splits the tokens dataset, collected per project, into training, validation and test sets.
# The script divides the dataset per numbers of datapoints, and collect them into three bins: Small,
# Medium, and Large.
# The script select random projects from each bin, according to the ratio between training, validation and test sets.
# The script uses a seed to reproduce the same results.
# The script generates three .csv files containing the list of projects for each set.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"

if [ $# -eq 1 ]; then
    config_paths=("$1")
else
    config_paths=("${PROMPT_DIR}/resources/config/"*/)
fi

for config_path in "${config_paths[@]}"; do
  config_num=$(basename "${config_path}")
  i=$(basename "$config_num")
  "$PY_ENV" "${PROMPT_DIR}/utils/split_dataset.py" \
    --data-dir              "${OUTPUT_DIR}/${i}/prompt-jsonl" \
    --out-dir               "${OUTPUT_DIR}/${i}/split-dataset" \
    --min-oracles-for-test  "100" \
    --seed                  "42"  \
    --train-ratio           "0.65" \
    --val-ratio             "0.1" \
    --test-ratio            "0.25"
done