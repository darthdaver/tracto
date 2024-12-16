#!/bin/bash
# The script generates the Tokens dataset starting from a list of repositories, in 3 steps:
#   1. The script reads the CSV file line by line and split into repo_id, repo_url, and commit_sha fields
#   2. The script clones the repository and checkout the commit version
#   3. The script generates the Tokens dataset
# The final dataset is stored in the `src/main/resources/dataset/tokens` directory

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../../utils/global_variables.sh"
source "${UTILS_DIR}/init_sdkman.sh"

# Add Maven bin directory to PATH temporarily
export PATH="$MAVEN_BIN:$PATH"
export PATH="$GRADLE_BIN:$PATH"
export PATH="$ANT_BIN:$PATH"

M2T_REPO_LIST=${1:-$M2T_REPO_LIST}
processed_file="${main_folder}/processed_projects.txt"
skipped_file="${main_folder}/skipped_projects.txt"

if [ ! -e "${TRATTO_JAR}" ]; then
  cd "${ROOT_DIR}"
  mvn clean package -DskipTests
fi
if [ -f "$processed_file" ]; then
    # Remove the file
    rm "$processed_file"
fi
if [ -f "$skipped_file" ]; then
    # Remove the file
    rm "$skipped_file"
fi

check_maven() {
    [[ -f "pom.xml" ]] && return 0 || return 1
}

# Read the CSV file line by line and split into project_id, bug_id, and modified_classes fields
while IFS=, read -r repo_id repo_url commit_sha; do
    cd "$ROOT_DIR"
    if [ ! -d "${GITHUB_REPOS_TMP_DIR}/${repo_id}" ]; then
      # Clean modified_classes string from undesired white-spaces/line-breaks introduced with the CSV parsing
      commit_sha="${commit_sha//[$'\t\r\n ']/}"
      # Clone project
      echo "Cloning project: ${repo_id}."
      git clone "${repo_url}" "${GITHUB_REPOS_TMP_DIR}/${repo_id}"
      # Checkout specific commit version of the project
      echo "Checkout commit version: ${repo_id} - ${commit_sha}."
      cd "${GITHUB_REPOS_TMP_DIR}/${repo_id}"
      git checkout "${commit_sha}"
    fi

    if check_maven; then
      echo "$repo_id" >> "$processed_file"
      if [ ! -d "${GITHUB_REPOS_TMP_DIR}/${repo_id}/tratto_libs" ]; then
        # Setup dependencies
        bash "${UTILS_DIR}/resolve_dependencies.sh" "${GITHUB_REPOS_TMP_DIR}/${repo_id}"
      fi
      cd "$ROOT_DIR"
      # Setup java libraries paths
      classpath="${LIB_DIR}/java8:${LIB_DIR}/java11:${LIB_DIR}/java17"
      # Generate Oracle dataset
      #sdk use java $JAVA17
      #java -jar "${TRATTO_JAR}" "oracles-dataset" "${GITHUB_REPOS_TMP_DIR}/${repo_id}" "${OUTPUT_DIR}/${repo_id}" "${classpath}"
    else
      echo "$repo_id" >> "$skipped_file"
    fi
done < "$M2T_REPO_LIST"