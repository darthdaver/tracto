# The script run the inference task with the vanilla llms required to replicate RQ1

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../scripts/utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${UTILS_DIR}/bash/init_sdkman.sh"

JAVA_VERSIONS=(
    "$JAVA11"
    "$JAVA17"
    "$JAVA21"
    "$JAVA24"
    "$JAVA8"
)


process_tests() {
  local repo_name="$1"
  local config_num="$2"
  local java_version="$3"
  local input_inference_file_path="$4"
  local test_type="$5"

  # Step 2: Generate classpath for test compilation (dependencies + main classes)
  mvn dependency:build-classpath -Dmdep.outputFile=cp.txt -q
  # Add main classes to classpath
  classpath="${GITHUB_REPOS_DIR}/${repo_name}/target/classes:${GITHUB_REPOS_DIR}/${repo_name}/target/test-classes:$(cat cp.txt)"
  sdk use java "$JAVA21" > /dev/null
  output_inference_file_path="${input_inference_file_path%.json}.txt"
  java -jar "${MUTATION_JAR}" "${GITHUB_REPOS_DIR}/${repo_name}" "${input_inference_file_path}" "${GITHUB_REPOS_DIR}/${repo_name}/star-classes" "${SDKMAN_DIR}/candidates/java/${java_version}" "${SDKMAN_DIR}/candidates/maven/${MAVEN_VERSION}" "${test_type}" "${classpath}"
}


# Add Maven bin directory to PATH temporarily
export PATH="$MAVEN_BIN:$PATH"

source "${current_dir}/../../.venv/bin/activate"

if [ ! -e "${MUTATION_JAR}" ]; then
  cd "${RQS_DIR}"
  sdk use java "$JAVA21"
  mvn clean package -DskipTests
fi

inference_dir_path=${1}
correctness_dir="$(dirname "${inference_dir_path}")"

for inference_file_path in "${inference_dir_path}"/*; do
  file_name=$(basename "$inference_file_path")
  config_num=$(basename $(dirname "$(dirname "${inference_file_path}")"))
  repo_info=$("${PY_ENV}" "${RQS_DIR}/utils/extract_repo_id_name.py" "${file_name}" "${GITHUB_REPOS_LIST_FILE}")
  echo "-----------------------"
  echo "File name: ${file_name}"
  echo "Config: ${config_num}"
  echo "Repo info: ${repo_info}"
  echo "-----------------------"
  if [ $? -eq 0 ]; then
    repo_id=$(echo "$repo_info" | cut -d' ' -f1)
    repo_name=$(echo "$repo_info" | cut -d' ' -f2)
    repo_url="${GITHUB_BASE_URL}/${repo_name}.git"
    if [ ! -d "${GITHUB_REPOS_DIR}/${repo_name}" ]; then
      # Clone project
      echo "Cloning project in folder: ${GITHUB_REPOS_DIR}/${repo_name}"
      git clone "${repo_url}" "${GITHUB_REPOS_DIR}/${repo_name}"|| echo "Error cloning repository: ${repo_url}" >&2
    fi
    for version in "${JAVA_VERSIONS[@]}"; do
        echo "Processing project with Java version: ${version}"
        echo "-----------------------"
        sdk use java "$version"
        fqn_classes=$("${PY_ENV}" "${RQS_DIR}/utils/extract_test_classes_fqn.py" "${inference_file_path}")
        cd "${GITHUB_REPOS_DIR}/${repo_name}"
        src_classes=$(echo "$fqn_classes" | jq -r '.classes | @sh' | tr -d \')
        test_classes=$(echo "$fqn_classes" | jq -r '.test_classes | @sh' | tr -d \')
        echo "Source classes: [ ${src_classes} ]"
        echo "Test classes: [ ${test_classes} ]"
        if [ -d "${correctness_dir}/correctness/${repo_id}" ]; then
            rm -rf "${correctness_dir}/correctness/${repo_id}"
        fi
        echo "--------------------------"
        echo "Processing original project"
        echo "--------------------------"
        find "${GITHUB_REPOS_DIR}/${repo_name}" -type f -name '*STARSplitTestInferenceTest.java' -delete
        find "${GITHUB_REPOS_DIR}/${repo_name}" -type f -name '*STARSplitTestNoOracleTest.java' -delete
        echo "Starting compiling..."
        mvn clean install -DskipTests -Dgpg.skip=true -Dspotless.check.skip=true -Dmaven.compiler.failOnWarning=false -Dspotless.check.skip=true -Dcheckstyle.skip=true -Dlicense.skip=true -Drat.skip=true -Ddependency-check.skip=true #> /dev/null 2>&1
        if [ $? -eq 0 ]; then
          echo "Compilation successful for ${repo_name} with Java version ${version}"
        else
          echo "Compilation failed for ${repo_name} with Java version ${version}"
          continue
        fi
        process_tests \
          "$repo_name" \
          "$config_num" \
          "$version" \
          "$inference_file_path" \
          "no_oracle"
        echo "Source classes: [ ${src_classes} ]"
        echo "Test classes: [ ${no_oracle_tests} ]"
        echo ""
        no_oracle_tests=$(head -n 1 "${GITHUB_REPOS_DIR}/${repo_name}/NO_ORACLE_classes_processed.csv" | tr -d '\r\n')
        echo "----------------------------"
        echo "Processing Inference oracles"
        echo "----------------------------"
        process_tests \
          "$repo_name" \
          "$config_num" \
          "$version" \
          "$inference_file_path" \
          "inference"
        inference_tests=$(head -n 1 "${GITHUB_REPOS_DIR}/${repo_name}/INFERENCE_classes_processed.csv" | tr -d '\r\n')
        echo "Source classes: [ ${src_classes} ]"
        echo "Test classes: [ ${inference_tests} ]"
        rm -r "${GITHUB_REPOS_DIR}/${repo_name}/star-classes"
        break
    done
  else
    echo "Repo not found from file_name: ${file_name}"
  fi
done

