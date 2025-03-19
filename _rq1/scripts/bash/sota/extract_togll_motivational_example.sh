# The script download and setup the sota approach TOGLL, and extract from the
# training dataset the motivational example used in the paper.

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")
# Setup global variables
source "${current_dir}/../utils/global_variables.sh"

if [ ! -d "${TOGLL_DIR}" ]; then
  # Download TOGGL
  echo "Downloading TOGLL replication package"
  curl -o "${ROOT_DIR}/togll.zip" "${RQ1_SOTA_TOGLL_PROJECT_LINK}"
  # Extract TOGGL
  mkdir -p "${TOGLL_DIR}"
  echo "Extracting TOGLL replication package"
  unzip "${ROOT_DIR}/togll.zip" -d "${TOGLL_DIR}"
  unzip "${TOGLL_DIR}/togll-artifacts.zip" -d "${TOGLL_DIR}"
  rm "${ROOT_DIR}/togll.zip"
  rm "${TOGLL_DIR}/togll-artifacts.zip"
fi

# Extract motivational example
echo "Extracting motivational example from TOGLL dataset (codeparrot - mut | training - datapoint num. 112478)"
"${PY_BIN}" "${PY_SOTA_DIR}/extract_togll_motivational_example.py" \
  "codeparrot/codeparrot-small-multi" \
  "${TOGLL_DIR}/togll-artifacts/RQ1/fine-tuning/dataset/dataset_codeparrot.pickle" \
  "${ROOT_DIR}/togll_motivational_example.txt" \
  "112478"