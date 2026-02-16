#!/bin/bash
# The script generates the raw tokens dataset starting from a list of repositories provided in a csv file and the
# corresponding raw oracles dataset previously generated.
# By default, the script uses the list of repositories saved in the global variable GITHUB_REPOS_LIST_FILE defined in the
# corresponding file of global variables (`scripts/utils/bash/global_variables.sh`).
# However, the user can provide a different list of repositories by passing the absolute file path as the first argument
# to the script.
# The final dataset is stored in the `output/raw-tokens-dataset-training` directory (statistics are saved in
# `output/raw-oracles/statistics`).

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"
source "${UTILS_DIR}/bash/init_sdkman.sh"

GITHUB_REPOS_LIST_FILE=${1:-$GITHUB_REPOS_LIST_FILE}
resolve_deps=${2:-"false"}

# Collect the raw oracles dataset repo ids
while IFS= read -r -d $'\0' dir; do
    raw_oracles_dataset_repo_ids+=("$(basename "$dir")")
done < <(find "${RAW_ORACLES_DATASET_TRAINING_DIR}" -mindepth 1 -maxdepth 1 -type d -print0)

cd "${SYMBOLIC_DIR}"
sdk use java "$JAVA21"
mvn clean package -DskipTests