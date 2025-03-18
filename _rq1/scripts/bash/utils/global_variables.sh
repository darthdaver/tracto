# Directories
ROOT_DIR=$(dirname "$(dirname "$(dirname "$(realpath "$(dirname "$BASH_SOURCE")")")")")
PY_BIN="${ROOT_DIR}/../neural-module/.venv/bin/python3"
BASH_DIR="${ROOT_DIR}/scripts/bash"
PY_DIR="${ROOT_DIR}/scripts/python"
BASH_UTILS_DIR="${BASH_DIR}/utils"
PY_UTILS_DIR="${PY_DIR}/utils"
BASH_RESOURCES_DIR="${ROOT_DIR}/scripts/bash/resources"
BASH_INFERENCE_DIR="${BASH_DIR}/inference"
PY_INFERENCE_DIR="${PY_DIR}/inference"
SCRIPTS_RESOURCES_DIR="${ROOT_DIR}/scripts/resources"
RAW_ORACLES_DATASET_DIR="${ROOT_DIR}/../symbolic-module/output/raw-oracles-dataset"
RAW_TOKENS_DATASET_DIR="${ROOT_DIR}/../symbolic-module/output/raw-tokens-dataset"
LLM_ORACLES_DATASET_DIR="${ROOT_DIR}/llm-oracles-dataset"
LLM_TOKENS_DATASET_DIR="${ROOT_DIR}/llm-tokens-dataset"
SDKMAN_DIR="${BASH_RESOURCES_DIR}/sdkman"
RQ1_DATASET_DIR="${ROOT_DIR}/rq1-dataset"
TOGLL_DIR="${ROOT_DIR}/togll"

# Bin
MAVEN_BIN="${SDKMAN_DIR}/candidates/maven/${MAVEN_VERSION}/bin"
GRADLE_BIN="${SDKMAN_DIR}/candidates/gradle/${GRADLE_VERSION}/bin"
ANT_BIN="${SDKMAN_DIR}/candidates/ant/${ANT_VERSION}/bin"

# Files
RQ1_MODELS_LIST="${SCRIPTS_RESOURCES_DIR}/models-list.csv"

# Links
RQ1_MOTIVATIONAL_PROJECT_EXAMPLE_LINK="https://drive.switch.ch/index.php/s/zRkRD5kWLL92zVR/download"
RQ1_SOTA_TOGLL_PROJECT_LINK="https://figshare.com/ndownloader/articles/28282721/versions/3"