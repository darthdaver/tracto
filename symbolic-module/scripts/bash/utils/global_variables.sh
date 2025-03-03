# Sdkman Java versions
JAVA8="8.0.392-amzn"
JAVA11="11.0.21-amzn"
JAVA17="17.0.8-oracle"
JAVA21="21.0.6-amzn"
# Sdkman Maven version
MAVEN_VERSION="3.9.4"
# Sdkman Ant version
ANT_VERSION="1.10.13"
# Sdkman Gradle version
GRADLE_VERSION="8.3"

# Directories
ROOT_DIR=$(dirname "$(dirname "$(dirname "$(realpath "$(dirname "$BASH_SOURCE")")")")")
BASH_DIR="${ROOT_DIR}/scripts/bash"
PY_DIR="${ROOT_DIR}/scripts/python"
BASH_RESOURCES_DIR="${BASH_DIR}/resources"
BASH_UTILS_DIR="${BASH_DIR}/utils"
PY_UTILS_DIR="${PY_DIR}/utils"
INPUT_DIR="${ROOT_DIR}/input"
OUTPUT_DIR="${ROOT_DIR}/output"
SDKMAN_DIR="${BASH_RESOURCES_DIR}/sdkman"
GITHUB_REPOS_TMP_DIR="${INPUT_DIR}/tmp"

# Files
M2T_REPO_LIST="${ROOT_DIR}/src/main/resources/dataset/m2t_repos.csv"

# Libraries
LIB_DIR="${ROOT_DIR}/lib"

# Bin
MAVEN_BIN="${SDKMAN_DIR}/candidates/maven/${MAVEN_VERSION}/bin"
GRADLE_BIN="${SDKMAN_DIR}/candidates/gradle/${GRADLE_VERSION}/bin"
ANT_BIN="${SDKMAN_DIR}/candidates/ant/${ANT_VERSION}/bin"

# Jar
TRACTO_JAR="${ROOT_DIR}/target/tracto-jar-with-dependencies.jar"
FERNFLOWER_JAR="${BASH_RESOURCES_DIR}/fernflower.jar"