#!/bin/bash

# Main folder where the search begins
MAIN_FOLDER="$1"

if [ -z "$MAIN_FOLDER" ]; then
    echo "Usage: $0 <path-to-main-folder>"
    exit 1
fi

# Find all build-related files in the directory hierarchy
find "$MAIN_FOLDER" -type f \( -name "pom.xml" -o -name "build.gradle" -o -name "build.xml" \) | while read -r BUILD_FILE; do
    # Determine the project directory and initialize lib folder
    PROJECT_DIR=$(dirname "$BUILD_FILE")
    LIB_DIR="$PROJECT_DIR/lib_tratto"
    mkdir -p "$LIB_DIR"

    echo "Processing project in: $PROJECT_DIR"

    if [[ "$BUILD_FILE" == *pom.xml ]]; then
        echo "Detected Maven project."
        # Run Maven to download dependencies
        (cd "$PROJECT_DIR" && mvn dependency:copy-dependencies -DoutputDirectory="$LIB_DIR")
    elif [[ "$BUILD_FILE" == *build.gradle ]]; then
        echo "Detected Gradle project."
        # Add a temporary Gradle task to copy dependencies
        TEMP_TASK="task copyDependencies(type: Copy) {
    from configurations.runtimeClasspath
    into '$LIB_DIR'
}"
        # Append the temporary task, run it, then remove it
        echo "$TEMP_TASK" >> "$BUILD_FILE"
        (cd "$PROJECT_DIR" && ./gradlew copyDependencies)
        # Remove the temporary task
        sed -i '/task copyDependencies/,/}/d' "$BUILD_FILE"

    elif [[ "$BUILD_FILE" == *build.xml ]]; then
        echo "Detected Ant project."
        if grep -q "<ivy" "$BUILD_FILE"; then
            echo "Detected Ivy dependency management."
            # Resolve Ivy dependencies and copy to lib
            (cd "$PROJECT_DIR" && ant resolve)
            cp -r ~/.ivy2/cache/* "$LIB_DIR"
        else
            echo "Standard Ant project. Looking for <get> resources..."
            # Parse and download <get> resources in build.xml
            grep -oP '(?<=<get src=").*?(?=")' "$BUILD_FILE" | while read -r URL; do
                wget -P "$LIB_DIR" "$URL"
            done
        fi
    else
        echo "Unrecognized build tool for $PROJECT_DIR. Skipping."
    fi
    echo "Dependencies for $PROJECT_DIR have been stored in $LIB_DIR."
done