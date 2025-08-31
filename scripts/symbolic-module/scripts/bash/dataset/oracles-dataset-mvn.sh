#!/bin/bash
# The script generates the raw oracles dataset starting from a list of repositories provided in a csv file.
# By default, the script uses the list of repositories saved in the resources folder (`tracto/resources/m2t_repos.csv`).
# However, the user can provide a different list of repositories by passing the absolute file path as the first argument
# to the script.
# The script operates in 4 steps, iteratively:
#   1. The script reads the current line of the CSV file and split it into repo_id, repo_url, and commit_sha fields
#   2. The script clones the repository and checkout the commit version
#   3. The script detects the type of project (Maven, Gradle, Ant) and processes it accordingly (e.g. download maven
#      dependencies, if available)
#   4. The script generates the raw oracles dataset for the current project
# The final dataset is stored in the `output/raw-oracles/dataset` directory (statistics are saved in
# `output/raw-oracles/statistics`).

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
# Setup global & local variables
source "${current_dir}/../../../../utils/bash/global_variables.sh"
source "${current_dir}/../utils/local_variables.sh"
source "${UTILS_DIR}/bash/init_sdkman.sh"

# Add Maven bin directory to PATH temporarily
export PATH="$MAVEN_BIN:$PATH"
export PATH="$GRADLE_BIN:$PATH"
export PATH="$ANT_BIN:$PATH"

GITHUB_REPOS_LIST_FILE=${1:-$GITHUB_REPOS_LIST_FILE}
resolve_deps=${2:-"false"}

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

# Read the CSV file line by line and split into project_id, bug_id, and modified_classes field
while IFS=, read -r repo_id repo_name; do
    # Define a log file at the top of the script
    log_file="${OUTPUT_DIR}/logs/oracles-dataset-mvn_log_${repo_id}.log"
    mkdir -p "$(dirname "$log_file")"
    cd "$ROOT_DIR"
    # Clean repo_name string from undesired white-spaces/line-breaks introduced with the CSV parsing
    repo_name="${repo_name//[$'\t\r\n ']/}"
    echo "==============================" >> "$log_file"
    echo "Repo: $repo_name  ID: $repo_id" >> "$log_file"
    echo "==============================" >> "$log_file"
    repo_url="${GITHUB_BASE_URL}/${repo_name}.git"
    if [ ! -d "${GITHUB_REPOS_DIR}/${repo_name}" ]; then
      # Clean modified_classes string from undesired white-spaces/line-breaks introduced with the CSV parsing
      commit_sha="${commit_sha//[$'\t\r\n ']/}"
      # Clone project
      echo "Cloning project in folder: ${GITHUB_REPOS_DIR}/${repo_name}" >> "$log_file"
      git clone "${repo_url}" "${GITHUB_REPOS_DIR}/${repo_name}"|| echo "Error cloning repository: ${repo_url}" >&2
    fi
    project_type=$(scan_projects "${GITHUB_REPOS_DIR}/${repo_name}")
    # Check if the project is a Maven project and if it has only one pom.xml file and uses JUnit as plugin
    if [ "$project_type" == "maven" ]; then
      echo "Maven project detected" >> "$log_file"
      # Check how many pom.xml files are in the project
      cd "${GITHUB_REPOS_DIR}/${repo_name}"
      pom_count=$(find . -name "pom.xml" | wc -l)
      echo "POM count: $pom_count" >> "$log_file"
      # Check if the project uses JUnit as plugin to run the tests
      if grep -qE '<artifactId>junit|junit-jupiter' pom.xml || grep -qE '<groupId>junit|org.junit.jupiter' pom.xml; then
          echo "JUnit plugin detected" >> "$log_file"
          junit_plugin=true
      else
          junit_plugin=false
      fi

#      if [[ "$pom_count" -eq 1 && "$junit_plugin" == true ]]; then
#        if [ ! -d "${GITHUB_REPOS_DIR}/${repo_name}/processed_libs" ]; then
#          echo "Processing dependencies of project"  >> "$log_file"
#          # Setup dependencies
#          if [ "$resolve_deps" == "true" ]; then
#            bash "${BASH_UTILS_DIR}/resolve_dependencies_mvn.sh" "${GITHUB_REPOS_DIR}/${repo_name}"
#          fi
#        fi
#        cd "$SYMBOLIC_DIR"
#        # Generate Oracle dataset
#        sdk use java "$JAVA21"
#        java -jar "${SYMBOLIC_JAR}" "oracles" "${repo_id}" "${GITHUB_REPOS_DIR}/${repo_name}" "${OUTPUT_DIR}" "${SYMBOLIC_DIR}" 24 "${SRC_RESOURCES_DIR}/dataset/oracles-dataset_config.json" >> "$log_file" 2>&1
#      fi

      echo "Maven processing code: ${repo_id}-${repo_name}-${project_type}-${pom_count}-${junit_plugin}" >> "$log_file"

      if [ "$junit_plugin" == true ]; then
        cd "$SYMBOLIC_DIR"
        # Generate Oracle dataset
        sdk use java "$JAVA21"
        java -jar "${SYMBOLIC_JAR}" "oracles" "${repo_id}" "${GITHUB_REPOS_DIR}/${repo_name}" "${OUTPUT_DIR}" "${SYMBOLIC_DIR}" 24 "${SRC_RESOURCES_DIR}/dataset/oracles-dataset_config.json" >> "$log_file" 2>&1
      else
        echo "Project ${repo_name} do not use JUnit library to test, skipping."  >> "$log_file"
      fi
    else
      echo "Project ${repo_name} is not a Maven project, skipping."  >> "$log_file"
    fi
done < "$GITHUB_REPOS_LIST_FILE"