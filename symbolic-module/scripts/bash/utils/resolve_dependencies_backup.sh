#!/bin/bash

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/global_variables.sh"
source "${UTILS_DIR}/init_sdkman.sh"

# Add Maven bin directory to PATH temporarily
export PATH="$MAVEN_BIN:$PATH"
export PATH="$GRADLE_BIN:$PATH"
export PATH="$ANT_BIN:$PATH"

MAIN_FOLDER="$1"
MAVEN_DEFAULT_REPO="https://repo1.maven.org/maven2"

## Function to resolve and download JARs
#download_maven_jar() {
#    local GROUP_ID="$1"
#    local ARTIFACT_ID="$2"
#    local VERSION="$3"
#    local OUTPUT_DIR="$4"
#    shift 4
#    local REPOS=("$MAVEN_DEFAULT_REPO" "$@")
#    mkdir -p "$OUTPUT_DIR"
#    for REPO in "${REPOS[@]}"; do
#      echo "Trying to download source JAR from: $REPO"
#      mvn dependency:get \
#        -Dartifact="$GROUP_ID:$ARTIFACT_ID:$VERSION:jar:sources" \
#        -Ddest="$OUTPUT_DIR" \
#        -DremoteRepositories="$REPO" -Dpackaging=jar \
#        && {
#          echo "$GROUP_ID:$ARTIFACT_ID:$VERSION" >> "$SUCCESSFUL_DOWNLOADS_FILE"
#          echo "Downloaded: $GROUP_ID:$ARTIFACT_ID:$VERSION from $REPO"
#          return 0
#        }
#      echo "Trying to download compiled JAR from: $REPO"
#      mvn dependency:get \
#        -Dartifact="$GROUP_ID:$ARTIFACT_ID:$VERSION" \
#        -Ddest="$OUTPUT_DIR" \
#        -DremoteRepositories="$REPO" \
#        && {
#          echo "$GROUP_ID:$ARTIFACT_ID:$VERSION" >> "$SUCCESSFUL_DOWNLOADS_FILE"
#          echo "Downloaded: $GROUP_ID:$ARTIFACT_ID:$VERSION from $REPO"
#          return 0
#        }
#    done
#    echo "$GROUP_ID:$ARTIFACT_ID:$VERSION" >> "$FAILED_DOWNLOADS_FILE"
#    echo "Error: Failed to download both source and compiled JAR for $GROUP_ID:$ARTIFACT_ID:$VERSION"
#}

download_maven_jar() {
    local GROUP_ID="$1"
    local ARTIFACT_ID="$2"
    local VERSION="$3"
    local OUTPUT_DIR="$4"
    shift 4
    local REPOS=("$MAVEN_DEFAULT_REPO" "$@")
    mkdir -p "$OUTPUT_DIR"
    for REPO in "${REPOS[@]}"; do
      echo "Trying to download source JAR from: $REPO"
      if mvn dependency:get \
        -Dartifact="$GROUP_ID:$ARTIFACT_ID:$VERSION:jar:sources" \
        -Ddest="$OUTPUT_DIR" \
        -DremoteRepositories="$REPO" \
        -Dpackaging=jar;
      then
          echo "$GROUP_ID:$ARTIFACT_ID:$VERSION" >> "$SUCCESSFUL_DOWNLOADS_FILE"
          echo "Downloaded: $GROUP_ID:$ARTIFACT_ID:$VERSION from $REPO"
          return 0
      fi
      echo "Trying to download compiled JAR from: $REPO"
      if mvn dependency:get \
        -Dartifact="$GROUP_ID:$ARTIFACT_ID:$VERSION" \
        -Ddest="$OUTPUT_DIR" \
        -DremoteRepositories="$REPO";
      then
          echo "$GROUP_ID:$ARTIFACT_ID:$VERSION" >> "$SUCCESSFUL_DOWNLOADS_FILE"
          echo "Downloaded: $GROUP_ID:$ARTIFACT_ID:$VERSION from $REPO"
          return 0
      fi
    done
    echo "$GROUP_ID:$ARTIFACT_ID:$VERSION" >> "$FAILED_DOWNLOADS_FILE"
    echo "Error: Failed to download both source and compiled JAR for $GROUP_ID:$ARTIFACT_ID:$VERSION"
    return 1
}

# Function to check if a folder is a Maven project
check_maven() {
    [[ -f "pom.xml" ]] && return 0 || return 1
}

# Function to check if a folder is a Gradle project
check_gradle() {
    [[ -f "build.gradle" || -f "settings.gradle" ]] && return 0 || return 1
}

# Function to check if a folder is an Ant project
check_ant() {
    [[ -f "build.xml" ]] && return 0 || return 1
}

echo "Resolving dependencies for: $MAIN_FOLDER"
# Main script execution
if [[ -d "$MAIN_FOLDER" ]]; then
    cd "$MAIN_FOLDER" || exit
    if check_maven; then
      project_poms_analysis=$(python3 "${UTILS_DIR}/python/resolve_dependency.py" "$MAIN_FOLDER" 2>/dev/null)
      pom_paths=$(echo "$project_poms_analysis" | jq -r 'keys[]')
      echo "$pom_paths" | while read -r pom_path; do
          echo "Processing: $pom_path"
          pom_dir=$(dirname $pom_path)
          dependencies=$(echo "$project_poms_analysis" | jq -r --arg key "$pom_path" '.[$key][0][]')
          repositories=$(echo "$project_poms_analysis" | jq -r --arg key "$pom_path" '.[$key][1][]')
          fails_file="${pom_dir}/failed_downloads.txt"
          success_file="${pom_dir}/successful_downloads.txt"
          echo "$dependencies" | while read -r dep; do
              IFS=':' read -r group_id artifact_id version <<< "$dep"
              output_dir="$pom_dir/mvn_libs"
              #download_maven_jar "$group_id" "$artifact_id" "$version" "$MAIN_FOLDER/new_lib" $repositories &
              mkdir -p "$output_dir"
              for repo in repositories; do
                echo "Trying to download source JAR from: $repo"
                if mvn dependency:get \
                  -Dartifact="$group_id:$artifact_id:$version:jar:sources" \
                  -Ddest="$output_dir" \
                  -DremoteRepositories="$repo" \
                  -Dpackaging=jar;
                then
                    echo "$group_id:$artifact_id:$version,sources" >> "$success_file"
                    echo "Downloaded: $group_id:$artifact_id:$version from $repo"
                else
                    echo "Trying to download compiled JAR from: $repo"
                    if mvn dependency:get \
                      -Dartifact="$group_id:$artifact_id:$version" \
                      -Ddest="$output_dir" \
                      -DremoteRepositories="$repo";
                    then
                        echo "$group_id:$artifact_id:$version,compiled" >> "$success_file"
                        echo "Downloaded: $group_id:$artifact_id:$version from $repo"
                    else
                        echo "$group_id:$artifact_id:$version" >> "$failed_downloads"
                        echo "Error: Failed to download both source and compiled JAR for $group_id:$artifact_id:$version"
                    fi
                fi
              done
          done
      done
    elif check_gradle; then
        exit 0
    elif check_ant; then
        exit 0
    else
        echo "This folder is not a recognized Maven, Gradle, or Ant project."
    fi
else
    echo "Usage: $0 <directory>"
fi
