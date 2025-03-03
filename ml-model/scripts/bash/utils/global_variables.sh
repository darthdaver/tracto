# Directories
ROOT_DIR=$(dirname "$(dirname "$(realpath "$(dirname "$BASH_SOURCE")")")")
BASH_DIR="${ROOT_DIR}/scripts/bash"
PY_DIR="${ROOT_DIR}/scripts/python"
BASH_UTILS_DIR="${BASH_DIR}/utils"
PY_UTILS_DIR="${PY_DIR}/utils"
BASH_RESOURCES_DIR="${ROOT_DIR}/scripts/bash/resources"
BASH_PREPROCESSING_DIR="${BASH_DIR}/preprocessing"
PY_PREPROCESSING_DIR="${PY_DIR}/preprocessing"
RAW_ORACLES_DATASET_DIR="${ROOT_DIR}/../tratto/raw-oracles-dataset"
RAW_TOKENS_DATASET_DIR="${ROOT_DIR}/../tratto/raw-tokens-dataset"
ML_ORACLES_DATASET_DIR="${ROOT_DIR}/oracles-dataset"
ML_TOKENS_DATASET_DIR="${ROOT_DIR}/tokens-dataset"
SDKMAN_DIR="${BASH_RESOURCES_DIR}/sdkman"

# Bin
MAVEN_BIN="${SDKMAN_DIR}/candidates/maven/${MAVEN_VERSION}/bin"
GRADLE_BIN="${SDKMAN_DIR}/candidates/gradle/${GRADLE_VERSION}/bin"
ANT_BIN="${SDKMAN_DIR}/candidates/ant/${ANT_VERSION}/bin"