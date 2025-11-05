#!/bin/bash

set -Eeuo pipefail

# Get current directory
current_dir=$(realpath "$(dirname "${BASH_SOURCE[@]}")")

# Setup global & local variables
source "${current_dir}/../../scripts/utils/bash/global_variables.sh"
source "${current_dir}/utils/local_variables.sh"

max_jobs=${1:-5}
split_dir=${2:-"${RESOURCES_DIR}/test-repos-split"}
bash_script_name=${3:-"rq1_tracto_inference.sh"}
config_num=${4:-1}
base_port=${5:-5000}

num_files=$(find "${split_dir}" -type f -name "*.csv" | wc -l)

# Arrays to track state
declare -A port_to_inference_pid  # Maps port -> inference PID
declare -A port_to_server_pid     # Maps port -> server PID
declare -A port_available         # Maps port -> 1 if available, 0 if busy
declare -A inference_pid_to_port  # Maps inference PID -> port (for reverse lookup)
declare -a all_ports              # Array to maintain port order

current_index=0

mkdir -p logs
mkdir -p pids

# Function to start a server on a given port
start_server() {
    local port=$1
    local server_log="logs/server_${port}.log"
    echo "Starting server on port ${port}..."
    nohup bash "${NEURAL_DIR}/bash/server.sh" "${config_num}" "${port}" > "${server_log}" 2>&1 &
    local server_pid=$!
    port_to_server_pid[$port]=$server_pid
    echo "${server_pid}" > "pids/server_${port}.txt"
    # Wait for server to be ready
    echo "Waiting for server on port ${port} to initialize..."
    local max_wait=300  # 5 minutes timeout
    local elapsed=0
    while ! curl -s "http://localhost:${port}/health" | grep -q '"status":"ready"' 2>/dev/null; do
        sleep 1
        ((elapsed++))
        if ((elapsed >= max_wait)); then
            echo "ERROR: Server on port ${port} failed to start after ${max_wait} seconds"
            return 1
        fi
    done
    echo "Server on port ${port} is ready (PID: ${server_pid})"
}

# Function to launch an inference job on a given port
launch_inference() {
    local index=$1
    local port=$2
    local split_file="${split_dir}/split_${index}.csv"
    local inference_log="logs/inference_${index}_port_${port}.log"
    echo "Launching inference for split_${index}.csv on port ${port}..."
    nohup bash "${current_dir}/${bash_script_name}" "${split_file}" "${config_num}" "${port}" > "${inference_log}" 2>&1 &
    local inference_pid=$!
    port_to_inference_pid[$port]=$inference_pid
    inference_pid_to_port[$inference_pid]=$port
    port_available[$port]=0  # Mark port as busy
    echo "${inference_pid}" > "pids/inference_${index}_port_${port}.txt"
    echo "Inference job ${index} started (PID: ${inference_pid}) on port ${port}"
}

# Function to check for completed inference jobs and free up ports
check_completed_jobs() {
    for port in "${all_ports[@]}"; do
        # Skip if no inference job is running on this port
        [[ -z "${port_to_inference_pid[$port]:-}" ]] && continue

        local pid=${port_to_inference_pid[$port]}
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "Inference job on port ${port} (PID: ${pid}) has completed"
            unset port_to_inference_pid[$port]
            unset inference_pid_to_port[$pid]
            port_available[$port]=1  # Mark port as available
        fi
    done
}

# Function to get an available port (returns first available in order)
get_available_port() {
    for port in "${all_ports[@]}"; do
        if [[ ${port_available[$port]} -eq 1 ]]; then
            echo "$port"
            return 0
        fi
    done
    return 1
}

# Phase 1: Start initial servers up to max_jobs
echo "Phase 1: Starting ${max_jobs} server instances..."
for ((i=0; i<max_jobs; i++)); do
    port=$((base_port + i))
    all_ports+=("$port")  # Store port in ordered array
    start_server "$port"
    port_available[$port]=1  # Mark as available
done

echo "All ${max_jobs} servers are running and ready"

# Phase 2: Launch inference jobs
echo "Phase 2: Launching inference jobs..."

while (( current_index < num_files )); do
    # Check for completed jobs to free up ports
    check_completed_jobs

    # Try to get an available port
    if available_port=$(get_available_port); then
        launch_inference "$current_index" "$available_port"
        ((current_index++))
    else
        # No available ports, wait a bit before checking again
        echo "All ports busy, waiting for a job to complete..."
        sleep 2
    fi
done

# Wait for all remaining inference jobs to complete
echo "All inference jobs have been launched. Waiting for completion..."
while [[ ${#port_to_inference_pid[@]} -gt 0 ]]; do
    check_complete
