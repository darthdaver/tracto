#!/bin/bash
# Usage: ./finetune_single_gpu.sh <gpu_id> <config>

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../scripts/utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${UTILS_DIR}/bash/init_sdkman.sh"

mkdir -p "${OUTPUT_RQ1}"

TEST_SET_REPOS_LIST_FILE="${1:-${TEST_SET_REPOS_LIST_FILE}}"
config_num=${2-1}
port=${3-5000}
mode="test"

while IFS=, read -r repo_id repo_name; do
    # Define a log file at the top of the script
    log_file="${OUTPUT_RQ1}/tracto/logs/${mode}/inference_log_${repo_id}.log"
    mkdir -p "$(dirname "$log_file")"
    cd "$ROOT_DIR"
    # Clean repo_name string from undesired white-spaces/line-breaks introduced with the CSV parsing
    repo_name="${repo_name//[$'\t\r\n ']/}"
    echo "==============================" >> "$log_file"
    echo "Repo: $repo_name  ID: $repo_id" >> "$log_file"
    echo "==============================" >> "$log_file"
    java -jar "${SYMBOLIC_JAR}" "${mode}" "${repo_id}" "${GITHUB_REPOS_DIR}/${repo_name}" "${OUTPUT_TRACTO_SYMBOLIC_DIR}" "${SYMBOLIC_DIR}" "${OUTPUT_SYMBOLIC_DIR}/raw-oracles-dataset-inference/${repo_id}" "${port}" "${config_num}" 24 >> "$log_file" 2>&1
done < "$TEST_SET_REPOS_LIST_FILE"