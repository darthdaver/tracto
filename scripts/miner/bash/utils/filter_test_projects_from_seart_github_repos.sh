#!/bin/bash
# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/local_variables.sh"
config_num=${1:-"1"}
GITHUB_REPOS_LIST_FILE=${2:-$GITHUB_REPOS_LIST_FILE}

"${PY_ENV}" "${TEST_MINER_DIR}/utils/filter_test_projects_from_seart_github_repos.py" \
    --test-projects-path "${PROMPT_DIR}/output/${config_num}/split-dataset/test_projects.csv" \
    --seart-repos-path "${GITHUB_REPOS_LIST_FILE}" \
    --output-path "${OUTPUT_DIR}/test_projects_miner.csv"