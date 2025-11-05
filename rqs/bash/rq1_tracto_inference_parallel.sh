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
bash_script_name=${3:-"rq1_tracto_inference.sh"}
config_num=${4:-1}
base_port=${5:-5000}

num_files=$(find "${split_dir}" -type f -name "*.csv" | wc -l)

# Arrays to track state
declare -A port_to_inference_pid
declare -A port_to_server_pid
declare -A port_available
declare -A inference_pid_to_port
declare -a all_ports

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

    echo "Waiting for server on port ${port} to initialize..."
    local max_wait=300
    local elapsed=0
    local check_interval=5

    while (( elapsed < max_wait )); do
        if ! kill -0 "$server_pid" 2>/dev/null; then
            echo "ERROR: Server process on port ${port} (PID: ${server_pid}) died"
            return 1
        fi

        if curl -s -f -m 2 "http://127.0.0.1:${port}/health" > /dev/null 2>&1; then
            echo "Server on port ${port} is ready (PID: ${server_pid})"
            return 0
        fi

        echo "  ... still waiting (${elapsed}s elapsed)"
        sleep "$check_interval"
        ((elapsed += check_interval))
    done

    echo "ERROR: Server on port ${port} timed out"
    return 1
}

# Function to launch an inference job on a given port
launch_inference() {
    local index=$1
    local port=$2
    local split_file="${split_dir}/split_${index}.csv"
    local inference_log="logs/inference_${index}_port_${port}.log"

    if [[ ! -f "$split_file" ]]; then
        echo "WARNING: Split file not found: ${split_file}"
        return 1
    fi

    echo "Launching inference for split_${index}.csv on port ${port}..."
    nohup bash "${current_dir}/${bash_script_name}" "${split_file}" "${config_num}" "${port}" > "${inference_log}" 2>&1 &
    local inference_pid=$!
    port_to_inference_pid[$port]=$inference_pid
    inference_pid_to_port[$inference_pid]=$port
    port_available[$port]=0
    echo "${inference_pid}" > "pids/inference_${index}_port_${port}.txt"
    echo "Inference job ${index} started (PID: ${inference_pid}) on port ${port}"
    return 0
}

# Function to check for completed inference jobs and free up ports
check_completed_jobs() {
    for port in "${all_ports[@]}"; do
        # Skip if no inference job is running on this port
        if [[ -z "${port_to_inference_pid[$port]:-}" ]]; then
            continue
        fi

        local pid=${port_to_inference_pid[$port]}

        # Check if process is still running
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "Inference job on port ${port} (PID: ${pid}) completed"
            unset "port_to_inference_pid[$port]"
            port_available[$port]=1
        fi
    done
}

# Phase 1: Start servers
echo "=========================================="
echo "Phase 1: Starting ${max_jobs} server instances..."
echo "=========================================="
for ((i=0; i<max_jobs; i++)); do
    port=$((base_port + i))
    all_ports+=("$port")

    if ! start_server "$port"; then
        echo "FATAL: Failed to start server on port ${port}"
        for p in "${all_ports[@]}"; do
            if [[ -n "${port_to_server_pid[$p]:-}" ]]; then
                kill "${port_to_server_pid[$p]}" 2>/dev/null || true
            fi
        done
        exit 1
    fi

    port_available[$port]=1
done

echo ""
echo "All ${max_jobs} servers running and ready"
echo ""

# Phase 2: Launch inference jobs
echo "=========================================="
echo "Phase 2: Launching inference jobs (${num_files} total files)..."
echo "=========================================="

while (( current_index < num_files )); do
    # Check for completed jobs to free up ports
    check_completed_jobs

    # Find first available port
    available_port=""
    for port in "${all_ports[@]}"; do
        if [[ ${port_available[$port]:-0} -eq 1 ]]; then
            available_port="$port"
            break
        fi
    done

    # If port available, launch inference
    if [[ -n "$available_port" ]]; then
        launch_inference "$current_index" "$available_port"
        current_index=$((current_index + 1))
        echo ">>> Progress: $current_index / $num_files jobs launched"
        echo ""
    else
        # No available ports, wait a bit before checking again
        echo "All $max_jobs ports busy ($current_index / $num_files launched), waiting for completion..."
        sleep 2
    fi
done

echo ""
echo "=========================================="
echo "Phase 3: Waiting for all jobs to complete..."
echo "=========================================="

# Phase 3: Wait for completion
job_count=0
while true; do
    check_completed_jobs
    job_count=${#port_to_inference_pid[@]}

    if (( job_count == 0 )); then
        break
    fi

    echo "Still waiting: $job_count job(s) running..."
    sleep 5
done

echo ""
echo "=========================================="
echo "Phase 4: Shutting down servers..."
echo "=========================================="

# Phase 4: Cleanup
for port in "${all_ports[@]}"; do
    if [[ -n "${port_to_server_pid[$port]:-}" ]]; then
        server_pid=${port_to_server_pid[$port]}
        if kill -0 "$server_pid" 2>/dev/null; then
            echo "Stopping server on port ${port} (PID: ${server_pid})"
            kill "$server_pid" 2>/dev/null || true
            sleep 1
        fi
    fi
done

echo ""
echo "=========================================="
echo "SUCCESS: Script completed!"
echo "=========================================="
exit 0
