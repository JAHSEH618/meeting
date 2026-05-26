#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# meeting-api (Java Spring Boot) Process and Compose Controller
# ─────────────────────────────────────────────────────────────────────────────
# This script manages the background lifecycle of meeting-api.
# It supports two running modes:
#   1. compose  - Runs via Docker Compose (production-grade standard container stack)
#   2. jar      - Runs via native standalone jar using java -jar (background process)
#
# Usage:
#   ./deploy/meeting-api-control.sh start [compose|jar] [--no-build]
#   ./deploy/meeting-api-control.sh stop [compose|jar]
#   ./deploy/meeting-api-control.sh restart [compose|jar] [--no-build]
#   ./deploy/meeting-api-control.sh status [compose|jar]
#   ./deploy/meeting-api-control.sh logs [compose|jar]
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="${REPO_ROOT}/deploy/meeting-api.pid"
LOG_FILE="${REPO_ROOT}/deploy/meeting-api.log"
JAR_PATH="${REPO_ROOT}/apps/meeting-api/meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar"
COMPOSE_SCRIPT="${REPO_ROOT}/deploy/meeting-api-compose.sh"
JAVA_RUN_SCRIPT="${REPO_ROOT}/deploy/meeting-api-java.sh"

# Color helpers
log()  { printf '\033[1;36m▸\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m⚠\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; }

usage() {
    cat <<EOF
Usage: $0 {start|stop|restart|status|logs} [mode] [options]

Modes:
  compose   - (Default) Docker Compose container mode (Standard production path)
  jar       - Standalone Jar process mode (nohup java -jar background daemon)

Options (for start/restart in compose mode):
  --no-build   Skip building the docker image before running.

Examples:
  $0 start compose              # Start containerized stack
  $0 stop compose               # Stop container stack
  $0 start jar                  # Start background jar process
  $0 status jar                 # Check background jar health
EOF
}

# Auto-detect default mode based on files or compose status
get_default_mode() {
    echo "compose"
}

is_jar_running() {
    if [ -f "${PID_FILE}" ]; then
        local pid
        pid=$(cat "${PID_FILE}")
        if ps -p "${pid}" > /dev/null 2>&1; then
            return 0
        fi
    fi
    return 1
}

# --- COMPOSE COMMANDS ---
start_compose() {
    log "Delegating start to Compose controller..."
    "${COMPOSE_SCRIPT}" start "$@"
}

stop_compose() {
    log "Delegating stop to Compose controller..."
    "${COMPOSE_SCRIPT}" stop
}

restart_compose() {
    log "Delegating restart to Compose controller..."
    "${COMPOSE_SCRIPT}" restart "$@"
}

status_compose() {
    log "Delegating status to Compose controller..."
    "${COMPOSE_SCRIPT}" ps
    log "Actuator status:"
    curl -s --connect-timeout 2 http://localhost:8080/actuator/health | jq . 2>/dev/null || warn "Java app is not responding on http://localhost:8080/actuator/health"
}

logs_compose() {
    log "Delegating logs to Compose controller..."
    "${COMPOSE_SCRIPT}" logs
}

# --- JAR COMMANDS ---
start_jar() {
    local no_build="${1:-}"

    if is_jar_running; then
        local pid
        pid=$(cat "${PID_FILE}")
        warn "meeting-api standalone jar is already running with PID ${pid}."
        return 0
    fi

    # 1. Preflight checks
    log "Running Java environment preflight checks..."
    "${JAVA_RUN_SCRIPT}" test --help >/dev/null 2>&1 || true

    # 2. Check for jar, compile if missing and --no-build not specified
    if [ ! -f "${JAR_PATH}" ]; then
        if [ "${no_build}" = "--no-build" ]; then
            err "Jar not found at ${JAR_PATH} and --no-build was specified."
            exit 1
        else
            log "Jar not found. Building Java modules..."
            "${JAVA_RUN_SCRIPT}" jar
        fi
    elif [ "${no_build}" != "--no-build" ]; then
        log "Rebuilding Java modules to ensure latest changes..."
        "${JAVA_RUN_SCRIPT}" jar
    fi

    # 3. Source environment configuration
    local env_file="${REPO_ROOT}/deploy/.meeting-api-prod.env"
    if [ ! -f "${env_file}" ]; then
        env_file="${REPO_ROOT}/deploy/.meeting-api-oss.env"
    fi

    if [ -f "${env_file}" ]; then
        log "Sourcing env configuration from ${env_file}..."
        set -a
        # shellcheck disable=SC1090
        . "${env_file}"
        set +a
    else
        warn "No environment configuration found (deploy/.meeting-api-prod.env or .meeting-api-oss.env)."
        warn "Running with system defaults. Ensure DB, MQ, and OSS variables are exported."
    fi

    log "Starting standalone jar in the background..."
    log "Logs redirecting to: ${LOG_FILE}"
    
    cd "${REPO_ROOT}"
    # Start jar in the background
    nohup java -jar "${JAR_PATH}" > "${LOG_FILE}" 2>&1 &
    local new_pid=$!
    echo "${new_pid}" > "${PID_FILE}"

    sleep 3
    if ps -p "${new_pid}" > /dev/null 2>&1; then
        log "meeting-api jar process started successfully with PID ${new_pid}."
        log "Verify health via: $0 status jar"
    else
        err "meeting-api failed to start. Last 15 lines of logs:"
        tail -n 15 "${LOG_FILE}" >&2
        rm -f "${PID_FILE}"
        exit 1
    fi
}

stop_jar() {
    if ! is_jar_running; then
        warn "meeting-api jar is not running (no active PID)."
        rm -f "${PID_FILE}"
        return 0
    fi

    local pid
    pid=$(cat "${PID_FILE}")
    log "Stopping standalone jar process (PID ${pid})..."
    
    kill -15 "${pid}" 2>/dev/null || true
    
    local timeout=15
    while [ $timeout -gt 0 ]; do
        if ! ps -p "${pid}" > /dev/null 2>&1; then
            break
        fi
        sleep 1
        timeout=$((timeout - 1))
    done

    if ps -p "${pid}" > /dev/null 2>&1; then
        warn "Graceful jar shutdown timed out. Sending SIGKILL..."
        kill -9 "${pid}" 2>/dev/null || true
    fi

    rm -f "${PID_FILE}"
    log "meeting-api jar stopped."
}

status_jar() {
    if is_jar_running; then
        local pid
        pid=$(cat "${PID_FILE}")
        log "meeting-api jar is ACTIVE with PID ${pid}."
        
        # Check port listening
        if command -v lsof >/dev/null 2>&1; then
            local listen
            listen=$(lsof -nP -iTCP:8080 -sTCP:LISTEN || true)
            if [ -n "${listen}" ]; then
                log "Port 8080: LISTENING"
                printf "%s\n" "${listen}"
            else
                warn "Port 8080: NOT LISTENING yet. Application may still be booting up..."
            fi
        else
            log "Port 8080: (lsof not available)"
        fi

        # Hit health indicators
        log "Polling actuator health..."
        curl -s --connect-timeout 2 http://localhost:8080/actuator/health/readiness | jq . 2>/dev/null || warn "Failed to reach /actuator/health/readiness"
        curl -s --connect-timeout 2 http://localhost:8080/actuator/health | jq '{status, components: {postgresRls, rabbitMqQueue, aiWorker}}' 2>/dev/null || warn "Failed to reach /actuator/health"
    else
        log "meeting-api jar is INACTIVE."
        if [ -f "${PID_FILE}" ]; then
            warn "PID file ${PID_FILE} exists but process is dead."
        fi
    fi
}

follow_logs_jar() {
    if [ -f "${LOG_FILE}" ]; then
        log "Following logs in ${LOG_FILE} (Ctrl-C to exit)..."
        tail -f "${LOG_FILE}"
    else
        err "Log file not found: ${LOG_FILE}"
        exit 1
    fi
}

main() {
    local cmd="${1:-}"
    if [ -z "${cmd}" ]; then
        usage
        exit 64
    fi
    shift

    local mode
    mode="${1:-$(get_default_mode)}"
    if [ "${mode}" != "compose" ] && [ "${mode}" != "jar" ]; then
        # Handle the case where option is --no-build instead of mode
        if [ "${mode}" = "--no-build" ]; then
            mode="$(get_default_mode)"
            # we inject --no-build back
            set -- "--no-build"
        else
            err "Invalid mode: '${mode}'. Must be 'compose' or 'jar'."
            usage >&2
            exit 64
        fi
    else
        shift
    fi

    log "Control Mode: ${mode}"

    case "${cmd}" in
        start)
            if [ "${mode}" = "compose" ]; then
                start_compose "$@"
            else
                start_jar "${1:-}"
            fi
            ;;
        stop)
            if [ "${mode}" = "compose" ]; then
                stop_compose
            else
                stop_jar
            fi
            ;;
        restart)
            if [ "${mode}" = "compose" ]; then
                restart_compose "$@"
            else
                stop_jar
                sleep 1
                start_jar "${1:-}"
            fi
            ;;
        status)
            if [ "${mode}" = "compose" ]; then
                status_compose
            else
                status_jar
            fi
            ;;
        logs)
            if [ "${mode}" = "compose" ]; then
                logs_compose
            else
                follow_logs_jar
            fi
            ;;
        *)
            usage >&2
            exit 64
            ;;
    esac
}

main "$@"
