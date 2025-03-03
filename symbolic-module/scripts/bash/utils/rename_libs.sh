# Get current directory
current_dir=$(realpath "$(dirname "$BASH_SOURCE")")
# Setup global variables
source "${current_dir}/global_variables.sh"

find "${INPUT_DIR}" -type d -name "tracto_libs" | while read dir; do
    mv "$dir" "$(dirname "$dir")/processed_libs"
done