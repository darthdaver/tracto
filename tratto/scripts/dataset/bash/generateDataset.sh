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
maven_projects_file="${ROOT_DIR}/maven_projects.csv"
gradle_projects_file="${ROOT_DIR}/gradle_projects.csv"
ant_projects_file="${ROOT_DIR}/ant_projects.csv"
unprocessed_projects_file="${ROOT_DIR}/unprocessed_projects.csv"

if [ ! -e "${TRATTO_JAR}" ]; then
  cd "${ROOT_DIR}"
  sdk use java $JAVA17
  mvn clean package -DskipTests
fi
if [ -f "$maven_projects_file" ]; then
  cd "${ROOT_DIR}"
  # Remove the file
  rm "$maven_projects_file"
  touch "$maven_projects_file"
  chmod 666 "$maven_projects_file"
fi
if [ -f "$gradle_projects_file" ]; then
  cd "${ROOT_DIR}"
  # Remove the file
  rm "$gradle_projects_file"
  touch "$gradle_projects_file"
  chmod 666 "$gradle_projects_file"
fi
if [ -f "$ant_projects_file" ]; then
  cd "${ROOT_DIR}"
  # Remove the file
  rm "$ant_projects_file"
  touch "$ant_projects_file"
  chmod 666 "$ant_projects_file"
fi
if [ -f "$unprocessed_projects_file" ]; then
  cd "${ROOT_DIR}"
  # Remove the file
  rm "$unprocessed_projects_file"
  touch "$unprocessed_projects_file"
  chmod 666 "$unprocessed_projects_file"
fi

check_maven() {
    [[ -f "pom.xml" ]] && return 0 || return 1
}

# Function to detect and classify projects
scan_projects() {
    local dir="$1"
    if [ -f "$dir/pom.xml" ]; then
        echo "maven"
    elif [ -f "$dir/build.gradle" ] || [ -f "$dir/build.gradle.kts" ] || [ -f "settings.gradle" ]; then
        echo "gradle"
    elif [ -f "$dir/build.xml" ] || [ -f "$dir/build/build.xml" ]; then
        echo "ant"
    else
        echo "unprocessed"
    fi
}

export -f scan_projects

# Read the CSV file line by line and split into project_id, bug_id, and modified_classes fields
while IFS=, read -r repo_id repo_url commit_sha; do
    cd "$ROOT_DIR"
    echo "Processing project: ${repo_id}."
    if [ ! -d "${GITHUB_REPOS_TMP_DIR}/${repo_id}" ]; then
      # Clean modified_classes string from undesired white-spaces/line-breaks introduced with the CSV parsing
      commit_sha="${commit_sha//[$'\t\r\n ']/}"
      # Clone project
      echo "Cloning project: ${repo_id}."
      git clone "${repo_url}" "${GITHUB_REPOS_TMP_DIR}/${repo_id}"|| echo "Error cloning repository: ${repo_url}" >&2
      # Checkout specific commit version of the project
      echo "Checkout commit version: ${repo_id} - ${commit_sha}."
      cd "${GITHUB_REPOS_TMP_DIR}/${repo_id}"
      git checkout "${commit_sha}" || echo "Error checking out commit: ${commit_sha} in repo: ${repo_id}" >&2
    fi
    project_type=$(scan_projects "${GITHUB_REPOS_TMP_DIR}/${repo_id}")
    if [ "$project_type" == "maven" ]; then
      echo "Maven project detected"
      echo "${repo_id},${repo_url},${commit_sha}" >> "$maven_projects_file"
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
    elif [ "$project_type" == "gradle" ]; then
      echo "Gradle project detected"
      echo "${repo_id},${repo_url},${commit_sha}" >> "$gradle_projects_file"
    elif [ "$project_type" == "ant" ]; then
      echo "Ant project detected"
      echo "${repo_id},${repo_url},${commit_sha}" >> "$ant_projects_file"
    else
      echo "Unprocessed project"
      echo "${repo_id},${repo_url},${commit_sha}" >> "$unprocessed_projects_file"
    fi
done < "$M2T_REPO_LIST"