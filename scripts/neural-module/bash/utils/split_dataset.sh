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

"$PY_ENV" "${NEURAL_DIR}/utils/split_dataset.py" \
  -data-dir              "${OUTPUT_PROMPT_JSONL_DATASET}" \
  -out_dir               "${OUTPUT_SPLIT_DIR}" \
  -min-oracles-for-test  "100" \
  -seed                  "42"  \
  -train-ratio           "0.65" \
  -val-ratio             "0.1" \
  -test-ratio            "0.25"