#!/bin/bash

# Remove -e from pipefail for now, we'll handle errors manually
set -uxo pipefail

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")

# Setup global & local variables
source "${current_dir}/../../scripts/utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"

max_jobs=${1:-5}
split_dir=${2:-"${RESOURCES_DIR}/test-repos-split"}
bash_script_name=${3:-"rq1_llm_f_inference_single_file.sh"}
config_num=${4:-1}
num_files=$(find "${split_dir}" -type f -name "*.csv" | wc -l)
current_index=0
running_pids=()

mkdir -p logs
mkdir -p pids

check_running_processes() {
    for i in "${!running_pids[@]}"; do
        if ! kill -0 "${running_pids[i]}" 2>/dev/null; then
            unset 'running_pids[i]'
            running_pids=("${running_pids[@]}")
            return
        fi
    done
}

while (( current_index < num_files )); do
    if (( ${#running_pids[@]} < max_jobs )); then
        nohup bash "${current_dir}/${bash_script_name}" "${split_dir}/split_${current_index}.csv" "${config_num}" 2>&1 &
        last_pid=$!
        echo "$last_pid" > "pids/pid-${current_index}.txt"
        running_pids+=("$last_pid")
        ((current_index++))
    fi
    sleep 2
    check_running_processes
done

wait "${running_pids[@]}"
echo "All processes have completed!"