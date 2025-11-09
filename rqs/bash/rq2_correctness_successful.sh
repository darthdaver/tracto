# The script run the inference task with the vanilla llms required to replicate RQ1

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global & local variables
source "${current_dir}/../../scripts/utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"
source "${UTILS_DIR}/bash/init_sdkman.sh"

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

tog=${1}
inference_dir_path=${2}
correctness_dir="$(dirname "${inference_dir_path}")"
successful_dir_path=${3}

exec 3< "$successful_dir_path"

# Read the CSV file line by line and split into project_id, bug_id, and modified_classes fields
while IFS=, read -r repo_name repo_id version <&3; do
  inference_file_path="${inference_dir_path}/${tog}-${repo_id}-oracles.json"
  inference_file_name=$(basename "$inference_file_path")
  config_num=$(basename "$(dirname "${inference_dir_path}")")
  # Clean version string from undesired white-spaces/line-breaks introduced with the CSV parsing
  version="${version//[$'\t\r\n ']/}"
  repo_url="${GITHUB_BASE_URL}/${repo_name}.git"
  echo "-----------------------"
  echo "File name: ${inference_file_name}"
  echo "Config: ${config_num}"
  echo "Repo info: ${repo_id} - ${repo_name} - ${repo_url}"
  echo "Java version: ${version}"
  echo "-----------------------"
  sdk use java "$version"
  echo "Extracting target source and test classes..."
  fqn_classes=$("${PY_ENV}" "${RQS_DIR}/utils/extract_test_classes_fqn.py" "${inference_file_path}")
  cd "${GITHUB_REPOS_DIR}/${repo_name}"
  src_classes=$(echo "$fqn_classes" | jq -r '.classes | @sh' | tr -d \')
  test_classes=$(echo "$fqn_classes" | jq -r '.test_classes | @sh' | tr -d \')
  echo "Source classes: [ ${src_classes} ]"
  echo "Test classes: [ ${test_classes} ]"
  if [ -d "${correctness_dir}/correctness/${repo_id}" ]; then
      rm -rf "${correctness_dir}/correctness/${repo_id}"
  fi
  echo "---------------------------"
  echo "Processing original project"
  echo "---------------------------"
  find "${GITHUB_REPOS_DIR}/${repo_name}" -type f -name '*_STAR_Split_inference.java' -delete
  find "${GITHUB_REPOS_DIR}/${repo_name}" -type f -name '*_STAR_Split_no_oracle.java' -delete
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
  no_oracle_tests=$(head -n 1 "${GITHUB_REPOS_DIR}/${repo_name}/NO_ORACLE_classes_processed.csv" | tr -d '\r\n')
  echo "Source classes: [ ${src_classes} ]"
  echo "No Oracle Test classes: [ ${no_oracle_tests} ]"
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
  echo "Inference Test classes: [ ${inference_tests} ]"
  echo "----------------------------"
  rm -r "${GITHUB_REPOS_DIR}/${repo_name}/star-classes"
done

exec 3<&-

