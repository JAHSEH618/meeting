#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ai-worker (Apple Silicon Mac) Background Process Controller
# ─────────────────────────────────────────────────────────────────────────────
# This script manages the background runtime lifecycle of the native
# Apple Silicon ai-worker process.
#
# Commands:
#   start     - Start ai-worker in the background (using nohup and a PID file)
#   stop      - Stop the background ai-worker process gracefully
#   restart   - Restart the background ai-worker process
#   status    - Check if the process is running, its uptime, and its health
#   logs      - Follow the output logs
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="${REPO_ROOT}/deploy/ai-worker.pid"
LOG_FILE="${REPO_ROOT}/deploy/ai-worker.log"
RUN_SCRIPT="${REPO_ROOT}/deploy/ai-worker-apple-silicon.sh"

# Color helpers
log()  { printf '\033[1;36m▸\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m⚠\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; }

usage() {
    cat <<EOF
Usage: $0 {start|stop|restart|status|logs} [env_type]

Commands:
  start     Start ai-worker in the background. If 'centos' is passed as the
            second argument, it uses deploy/.ai-worker-apple-silicon.env.centos,
            otherwise it uses deploy/.ai-worker-apple-silicon.env.
  stop      Stop the background ai-worker process.
  restart   Restart the background ai-worker process.
  status    Display whether the process is active, its PID, and port listening status.
  logs      Follow the background log file (deploy/ai-worker.log).

Options:
  env_type  Optional: 'local' (default) or 'centos' (to connect to remote Java).
EOF
}

is_running() {
    if [ -f "${PID_FILE}" ]; then
        local pid
        pid=$(cat "${PID_FILE}")
        if ps -p "${pid}" > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

start_worker() {
    local env_type="${1:-local}"
    local env_file="${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env"
    
    if [ "${env_type}" = "centos" ]; then
        env_file="${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env.centos"
        log "Mode: Connecting to CentOS Remote Java Server"
    else
        log "Mode: Local Standalone/Compose Mode"
    fi

    if is_running; then
        local pid
        pid=$(cat "${PID_FILE}")
        warn "ai-worker is already running with PID ${pid}."
        return 0
    fi

    # Ensure environment file exists
    if [ ! -f "${env_file}" ]; then
        if [ "${env_type}" = "centos" ]; then
            err "Configuration file not found: ${env_file}"
            err "Please create it first based on CentOS Java coordinates. See runbook for details."
            exit 1
        else
            log "No env file found. Generating a default env file first..."
            "${RUN_SCRIPT}" env
        fi
    fi

    log "Starting ai-worker in the background..."
    log "Environment: $(basename "${env_file}")"
    log "Logs will be written to: ${LOG_FILE}"

    # Export variables to let the run script load the appropriate file
    # We copy the selected env file to the default location temporarily or source it
    if [ "${env_type}" = "centos" ]; then
        cp "${env_file}" "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env.active"
        # We hook into the run script by replacing the default env file during launch
        # or we backup and swap
        mv "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env" "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env.bak" 2>/dev/null || true
        cp "${env_file}" "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env"
    fi

    # Launch script in the background
    cd "${REPO_ROOT}"
    # Force mock stage if not staged and no real weights downloaded yet to make sure it runs
    local models_root="${AI_WORKER_MODELS_ROOT:-${HOME}/meeting-models}"
    if [ ! -d "${models_root}" ]; then
        warn "Models directory ${models_root} not found. Running stage automatically to prepare mock weights..."
        "${RUN_SCRIPT}" stage
    fi

    nohup "${RUN_SCRIPT}" run > "${LOG_FILE}" 2>&1 &
    local new_pid=$!
    echo "${new_pid}" > "${PID_FILE}"
    
    # Restore env file if we backed it up
    if [ "${env_type}" = "centos" ]; then
        sleep 1 # Let the child process start and load the env
        mv "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env.bak" "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env" 2>/dev/null || true
        rm -f "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env.active"
    fi

    sleep 2
    if ps -p "${new_pid}" > /dev/null 2>&1; then
        log "ai-worker started successfully with PID ${new_pid}."
        log "Verify status via: $0 status"
    else
        err "ai-worker failed to start. Last 10 lines of logs:"
        tail -n 10 "${LOG_FILE}" >&2
        rm -f "${PID_FILE}"
        exit 1
    fi
}

stop_worker() {
    if ! is_running; then
        warn "ai-worker is not running (no active PID)."
        rm -f "${PID_FILE}"
        return 0
    fi

    local pid
    pid=$(cat "${PID_FILE}")
    log "Stopping background ai-worker process (PID ${pid})..."
    
    # Find child processes (like python or uv) to terminate them cleanly too
    local pids_to_kill
    pids_to_kill=$(pgrep -P "${pid}" || true)
    
    # Kill the parent background process
    kill -15 "${pid}" 2>/dev/null || true
    
    # Kill child processes
    if [ -n "${pids_to_kill}" ]; then
        log "Terminating child processes: ${pids_to_kill}"
        # shellcheck disable=SC2086
        kill -15 ${pids_to_kill} 2>/dev/null || true
    fi

    # Also clean up any lingering ai-worker-api python processes launched by this user
    local user_lingering
    user_lingering=$(pgrep -f "ai-worker-api" || true)
    if [ -n "${user_lingering}" ]; then
        log "Terminating lingering worker API processes: ${user_lingering}"
        # shellcheck disable=SC2086
        kill -15 ${user_lingering} 2>/dev/null || true
    fi

    # Uptime grace wait
    local timeout=10
    while [ $timeout -gt 0 ]; do
        if ! ps -p "${pid}" > /dev/null 2>&1 && ! pgrep -f "ai-worker-api" > /dev/null 2>&1; then
            break
        fi
        sleep 1
        timeout=$((timeout - 1))
    done

    if ps -p "${pid}" > /dev/null 2>&1 || pgrep -f "ai-worker-api" > /dev/null 2>&1; then
        warn "Graceful shutdown timed out. Sending SIGKILL..."
        kill -9 "${pid}" 2>/dev/null || true
        if [ -n "${pids_to_kill}" ]; then
            # shellcheck disable=SC2086
            kill -9 ${pids_to_kill} 2>/dev/null || true
        fi
        pkill -9 -f "ai-worker-api" 2>/dev/null || true
    fi

    rm -f "${PID_FILE}"
    log "ai-worker stopped."
}

status_worker() {
    if is_running; then
        local pid
        pid=$(cat "${PID_FILE}")
        log "ai-worker is ACTIVE with PID ${pid}."
        
        # Check port listening
        if command -v lsof >/dev/null 2>&1; then
            local listen
            listen=$(lsof -nP -iTCP:8090 -sTCP:LISTEN || true)
            if [ -n "${listen}" ]; then
                log "Port 8090: LISTENING"
                printf "%s\n" "${listen}"
            else
                warn "Port 8090: NOT LISTENING yet. Process may be starting up..."
            fi
        else
            log "Port 8090: (lsof not available, check raw process)"
        fi

        # Hit the readiness endpoint
        log "Polling health and readiness..."
        curl -s --connect-timeout 2 http://localhost:8090/internal/hardware | jq '{cuda, mps, models}' 2>/dev/null || warn "Failed to reach /internal/hardware"
        curl -s --connect-timeout 2 http://localhost:8090/internal/ready | jq . 2>/dev/null || warn "Failed to reach /internal/ready"
    else
        log "ai-worker is INACTIVE."
        if [ -f "${PID_FILE}" ]; then
            warn "Laying PID file ${PID_FILE} exists but process is dead."
        fi
    fi
}

follow_logs() {
    if [ -f "${LOG_FILE}" ]; then
        log "Following logs in ${LOG_FILE} (Ctrl-C to exit)..."
        tail -f "${LOG_FILE}"
    else
        err "Log file not found: ${LOG_FILE}"
        exit 1
    fi
}

main() {
    case "${1:-}" in
        start)
            shift
            start_worker "${1:-local}"
            ;;
        stop)
            stop_worker
            ;;
        restart)
            shift
            local env_type="${1:-local}"
            stop_worker
            sleep 1
            start_worker "${env_type}"
            ;;
        status)
            status_worker
            ;;
        logs)
            follow_logs
            ;;
        -h|--help|help)
            usage
            ;;
        *)
            usage >&2
            exit 64
            ;;
    esac
}

main "$@"
