#!/usr/bin/env bash
# ai-worker-web (React/Vite workstation) Background Process Controller
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_DIR="${REPO_ROOT}/apps/ai-worker-web"
PID_FILE="${REPO_ROOT}/deploy/ai-worker-web.pid"
LOG_FILE="${REPO_ROOT}/deploy/ai-worker-web.log"
PORT="${AI_WORKER_WEB_PORT:-5174}"
HOST="${AI_WORKER_WEB_HOST:-127.0.0.1}"

log()  { printf '\033[1;36m>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; }

usage() {
    cat <<EOF
Usage: $0 {start|stop|restart|status|logs} [local|centos]

Commands:
  start     Start ai-worker-web Vite dev server in the background.
  stop      Stop the background ai-worker-web process.
  restart   Restart ai-worker-web.
  status    Show process and port status.
  logs      Follow deploy/ai-worker-web.log.

Options:
  local     Proxy /api to http://localhost:8080 unless VITE_MEETING_API_TARGET is set.
  centos    Read deploy/.ai-worker-apple-silicon.env.centos when present and proxy
            /api to AI_WORKER_MEETING_API_BASE_URL or AI_WORKER_JAVA_API_BASE_URL.

Environment:
  AI_WORKER_WEB_PORT       Vite port, default 5174.
  AI_WORKER_WEB_HOST       Vite host, default 127.0.0.1. Use 0.0.0.0 for LAN.
  VITE_MEETING_API_TARGET  Explicit Java API proxy target.
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

meeting_api_target() {
    local env_type="${1:-local}"
    local target="${VITE_MEETING_API_TARGET:-}"
    local env_file=""

    if [ "${env_type}" = "centos" ]; then
        env_file="${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env.centos"
    elif [ -n "${AI_WORKER_WEB_ENV_FILE:-}" ]; then
        env_file="${AI_WORKER_WEB_ENV_FILE}"
    fi

    if [ -z "${target}" ] && [ -n "${env_file}" ] && [ -f "${env_file}" ]; then
        set -a
        # shellcheck disable=SC1090
        . "${env_file}"
        set +a
        target="${AI_WORKER_MEETING_API_BASE_URL:-${AI_WORKER_JAVA_API_BASE_URL:-}}"
    fi

    if [ -z "${target}" ]; then
        target="http://localhost:8080"
    fi
    printf '%s\n' "${target}"
}

start_web() {
    local env_type="${1:-local}"

    if is_running; then
        local pid
        pid=$(cat "${PID_FILE}")
        warn "ai-worker-web is already running with PID ${pid}."
        return 0
    fi

    if [ ! -d "${WEB_DIR}" ]; then
        err "Web app directory not found: ${WEB_DIR}"
        exit 1
    fi
    if [ ! -d "${WEB_DIR}/node_modules" ]; then
        err "Missing ${WEB_DIR}/node_modules. Run: cd apps/ai-worker-web && npm ci"
        exit 1
    fi

    local target
    target=$(meeting_api_target "${env_type}")

    log "Starting ai-worker-web on http://${HOST}:${PORT}/workstation/"
    log "Proxy /api -> ${target}"
    log "Logs will be written to: ${LOG_FILE}"

    cd "${WEB_DIR}"
    nohup env VITE_MEETING_API_TARGET="${target}" npm run dev -- --host "${HOST}" --port "${PORT}" --strictPort > "${LOG_FILE}" 2>&1 &
    local new_pid=$!
    echo "${new_pid}" > "${PID_FILE}"

    sleep 2
    if ps -p "${new_pid}" > /dev/null 2>&1; then
        log "ai-worker-web started successfully with PID ${new_pid}."
        log "Verify via: $0 status"
    else
        err "ai-worker-web failed to start. Last 15 lines of logs:"
        tail -n 15 "${LOG_FILE}" >&2 || true
        rm -f "${PID_FILE}"
        exit 1
    fi
}

stop_web() {
    local had_pid=false
    local pid=""
    if is_running; then
        had_pid=true
        pid=$(cat "${PID_FILE}")
        log "Stopping ai-worker-web process (PID ${pid})..."
        kill -15 "${pid}" 2>/dev/null || true
    else
        warn "ai-worker-web is not running (no active PID)."
    fi

    local timeout=10
    while [ "${had_pid}" = true ] && [ $timeout -gt 0 ]; do
        if ! ps -p "${pid}" > /dev/null 2>&1; then
            break
        fi
        sleep 1
        timeout=$((timeout - 1))
    done

    if [ "${had_pid}" = true ] && ps -p "${pid}" > /dev/null 2>&1; then
        warn "Graceful shutdown timed out. Sending SIGKILL..."
        kill -9 "${pid}" 2>/dev/null || true
    fi

    if command -v lsof >/dev/null 2>&1; then
        local port_pids
        port_pids=$(lsof -tiTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null || true)
        if [ -n "${port_pids}" ]; then
            warn "Terminating process still listening on port ${PORT}: ${port_pids}"
            # shellcheck disable=SC2086
            kill -15 ${port_pids} 2>/dev/null || true
        fi
    fi

    rm -f "${PID_FILE}"
    log "ai-worker-web stopped."
}

status_web() {
    if is_running; then
        local pid
        pid=$(cat "${PID_FILE}")
        log "ai-worker-web is ACTIVE with PID ${pid}."
    else
        log "ai-worker-web is INACTIVE."
        if [ -f "${PID_FILE}" ]; then
            warn "PID file ${PID_FILE} exists but process is dead."
        fi
    fi

    if command -v lsof >/dev/null 2>&1; then
        local listen
        listen=$(lsof -nP -iTCP:"${PORT}" -sTCP:LISTEN || true)
        if [ -n "${listen}" ]; then
            log "Port ${PORT}: LISTENING"
            printf "%s\n" "${listen}"
        else
            warn "Port ${PORT}: NOT LISTENING."
        fi
    fi

    if curl -fsS --connect-timeout 2 "http://localhost:${PORT}/workstation/" >/dev/null 2>&1; then
        log "HTTP check: OK http://localhost:${PORT}/workstation/"
    else
        warn "HTTP check failed for http://localhost:${PORT}/workstation/"
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
            start_web "${1:-local}"
            ;;
        stop)
            stop_web
            ;;
        restart)
            shift
            stop_web
            sleep 1
            start_web "${1:-local}"
            ;;
        status)
            status_web
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
