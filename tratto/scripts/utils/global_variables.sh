# Sdkman Java versions
JAVA8="8.0.392-amzn"
JAVA11="11.0.21-amzn"
JAVA17="17.0.8-oracle"
# Sdkman Maven version
MAVEN_VERSION="3.9.4"
# Sdkman Ant version
ANT_VERSION="1.10.13"
# Sdkman Gradle version
GRADLE_VERSION="8.3"

# Directories
ROOT_DIR=$(dirname "$(dirname "$(realpath "$(dirname "$BASH_SOURCE")")")")
UTILS_DIR="${ROOT_DIR}/scripts/utils"
INPUT_DIR="${ROOT_DIR}/input"
OUTPUT_DIR="${ROOT_DIR}/output"
SDKMAN_DIR="${RESOURCES_DIR}/sdkman"
GITHUB_REPOS_TMP_DIR="${ROOT_DIR}/input/tmp"
SDKMAN_DIR="${ROOT_DIR}/scripts/resources/sdkman"

# Files
M2T_REPO_LIST="${ROOT_DIR}/src/main/resources/dataset/m2t_repos.csv"

# Libraries
LIB_DIR="${ROOT_DIR}/lib"

# Bin
MAVEN_BIN="${SDKMAN_DIR}/candidates/maven/${MAVEN_VERSION}/bin"

# Jar
TRATTO_JAR="target/tratto-jar-with-dependencies.jar"