#!/bin/bash
# The script generates the raw tokens dataset starting from a list of repositories provided in a csv file and the
# corresponding raw oracles dataset previously generated.
# By default, the script uses the list of repositories saved in the resources folder (`tracto/resources/m2t_repos.csv`).
# However, the user can provide a different list of repositories by passing the absolute file path as the first argument
# to the script.
# The final dataset is stored in the `output/raw-tokens-dataset-training` directory (statistics are saved in
# `output/raw-oracles/statistics`).

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
# Setup global & local variables
source "${current_dir}/../../../utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${UTILS_DIR}/bash/init_sdkman.sh"

GITHUB_REPOS_LIST_FILE=${1:-$GITHUB_REPOS_LIST_FILE}
resolve_deps=${2:-"false"}

# Collect the raw oracles dataset repo ids
while IFS= read -r -d $'\0' dir; do
    raw_oracles_dataset_repo_ids+=("$(basename "$dir")")
done < <(find "${RAW_ORACLES_DATASET_TRAINING_DIR}" -mindepth 1 -maxdepth 1 -type d -print0)

if [ ! -e "${SYMBOLIC_JAR}" ]; then
  cd "${SYMBOLIC_DIR}"
  sdk use java "$JAVA21"
  mvn clean package -DskipTests
fi

if [ ! -d "${LIB_DIR}/java8" ] || [ ! -d "${LIB_DIR}/java11" ] || [ ! -d "${LIB_DIR}/java17" ] || [ ! -d "${LIB_DIR}/java21" ] || [ ! -d "${LIB_DIR}/java24" ]; then
    if [ -d "${LIB_DIR}" ]; then
        mv "${LIB_DIR}/java_classpaths.json" "${LIB_DIR}/../java_classpaths.json"
        rm -rf "${LIB_DIR}/"*
        mv "${LIB_DIR}/../java_classpaths.json" "${LIB_DIR}/java_classpaths.json"
    fi
    curl -o "${LIB_DIR}/jdks.zip" "${LIB_JDKS_LINK}"
    unzip "${LIB_DIR}/jdks.zip" -d "${LIB_DIR}"
    rm "${LIB_DIR}/jdks.zip"
fi

find_repo() {
    local name="$1"
    for dir in "${raw_oracles_dataset_repo_ids[@]}"; do
        if [[ "$dir" == "$name" ]]; then
            return 0  # true
        fi
    done
    return 1  # false
}

# Read the CSV file line by line and split into project_id, bug_id, and modified_classes field
while IFS=, read -r repo_id repo_name; do
    # Define a log file at the top of the script
    log_file="${OUTPUT_DIR}/logs/tokens-dataset_log_${repo_id}.log"
    mkdir -p "$(dirname "$log_file")"
    cd "$ROOT_DIR"
    # Clean repo_name string from undesired white-spaces/line-breaks introduced with the CSV parsing
    repo_name="${repo_name//[$'\t\r\n ']/}"
    echo "==============================" >> "$log_file"
    echo "Repo: $repo_name  ID: $repo_id" >> "$log_file"
    echo "==============================" >> "$log_file"
    repo_url="${GITHUB_BASE_URL}/${repo_name}.git"
    if [ ! -d "${GITHUB_REPOS_DIR}/${repo_name}" ]; then
      echo "Unexpected project repository not found in path: ${GITHUB_REPOS_DIR}/${repo_name}" >> "$log_file"
    fi
    # Check if the project is a Maven project and if it has only one pom.xml file and uses JUnit as plugin
    if find_repo "$repo_id"; then
      echo "Raw oracles dataset found for project: ${repo_name}"  >> "$log_file"
      cd "$SYMBOLIC_DIR"
      # Generate Tokens dataset for project
      sdk use java "$JAVA21"
      java -jar "${SYMBOLIC_JAR}" "tokens" "${repo_id}" "${GITHUB_REPOS_DIR}/${repo_name}" "${OUTPUT_DIR}" "${SYMBOLIC_DIR}" 24  >> "$log_file" 2>&1
    fi
done < "$GITHUB_REPOS_LIST_FILE"