#!/bin/bash
# The script clones the repositories provided in a csv file.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
GITHUB_REPOS_LIST_FILE=${1:-$GITHUB_REPOS_LIST_FILE}

# Read the CSV file line by line and split into project_id, bug_id, and modified_classes fields
while IFS=, read -r repo_id repo_name; do
    cd "$ROOT_DIR"
    # Clean repo_name string from undesired white-spaces/line-breaks introduced with the CSV parsing
    repo_name="${repo_name//[$'\t\r\n ']/}"
    echo "=============================="
    echo "Repo: $repo_name  ID: $repo_id"
    echo "=============================="
    # Clean repo_name string from undesired white-spaces/line-breaks introduced with the CSV parsing
    repo_name="${repo_name//[$'\t\r\n ']/}"
    echo "Processing project: ${repo_name}."
    repo_url="${GITHUB_BASE_URL}/${repo_name}.git"
    if [ ! -d "${GITHUB_REPOS_DIR}/${repo_name}" ]; then
      # Clean modified_classes string from undesired white-spaces/line-breaks introduced with the CSV parsing
      commit_sha="${commit_sha//[$'\t\r\n ']/}"
      # Clone project
      echo "Cloning project in folder: ${GITHUB_REPOS_DIR}/${repo_name}"
      git clone "${repo_url}" "${GITHUB_REPOS_DIR}/${repo_name}"|| echo "Error cloning repository: ${repo_url}" >&2
    fi
done < "$GITHUB_REPOS_LIST_FILE"