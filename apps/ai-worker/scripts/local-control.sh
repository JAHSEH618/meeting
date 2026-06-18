#!/usr/bin/env bash
# Local Mac controller for ai-worker API and ai-worker-web.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
API_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${API_DIR}/../.." && pwd)"
WEB_DIR="${REPO_ROOT}/apps/ai-worker-web"
RUN_DIR="${API_DIR}/.run"

API_PORT="${AI_WORKER_API_PORT:-8090}"
WEB_PORT="${AI_WORKER_WEB_PORT:-5174}"
WEB_HOST="${AI_WORKER_WEB_HOST:-127.0.0.1}"

API_PID_FILE="${RUN_DIR}/ai-worker-api.pid"
WEB_PID_FILE="${RUN_DIR}/ai-worker-web.pid"
API_LOG_FILE="${RUN_DIR}/ai-worker-api.log"
WEB_LOG_FILE="${RUN_DIR}/ai-worker-web.log"

log() { printf '[ai-worker] %s\n' "$*"; }
warn() { printf '[ai-worker] WARN: %s\n' "$*" >&2; }
err() { printf '[ai-worker] ERROR: %s\n' "$*" >&2; }

usage() {
    cat <<EOF
Usage: run one of the named scripts from apps/ai-worker

Local mode:
  ./all-start.sh       Start Python API + ai-worker-web locally.
  ./api-start.sh       Start only Python API locally.
  ./web-start.sh       Start only ai-worker-web locally.
  ./all-restart.sh     Restart Python API + ai-worker-web locally.
  ./all-stop.sh        Stop Python API + ai-worker-web.

CentOS integration mode:
  ./all-centos-start.sh    Start API + web using deploy/.ai-worker-apple-silicon.env.centos.
  ./api-centos-start.sh    Start only API using CentOS integration env.
  ./web-centos-start.sh    Start only web and proxy /api to the remote Java URL.
  ./all-centos-restart.sh  Restart API + web using CentOS integration env.
  ./all-centos-stop.sh     Stop API + web.

Status and logs:
  ./status.sh [api|web|all]
  ./logs.sh [api|web|all]

The named start/stop/restart scripts reject extra tail arguments.
EOF
}

normalize_service() {
    case "${1:-all}" in
        api|web|all) printf '%s\n' "${1:-all}" ;;
        local|centos) printf 'all\n' ;;
        *) err "invalid service '${1:-}'; expected api, web, or all"; exit 64 ;;
    esac
}

normalize_env() {
    local service_arg="${1:-all}"
    local env_arg="${2:-local}"
    if [ "${service_arg}" = "local" ] || [ "${service_arg}" = "centos" ]; then
        printf '%s\n' "${service_arg}"
    else
        case "${env_arg}" in
            local|centos|"") printf '%s\n' "${env_arg:-local}" ;;
            *) err "invalid env '${env_arg}'; expected local or centos"; exit 64 ;;
        esac
    fi
}

env_file_for() {
    local env_type="${1:-local}"
    if [ "${env_type}" = "centos" ]; then
        printf '%s\n' "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env.centos"
    else
        printf '%s\n' "${REPO_ROOT}/deploy/.ai-worker-apple-silicon.env"
    fi
}

load_api_env() {
    local env_type="${1:-local}"
    local env_file
    env_file="$(env_file_for "${env_type}")"
    if [ -f "${env_file}" ]; then
        log "Loading API env: ${env_file}"
        set -a
        # shellcheck disable=SC1090
        . "${env_file}"
        set +a
    elif [ "${env_type}" = "centos" ]; then
        err "missing env file: ${env_file}"
        exit 1
    else
        warn "API env file not found: ${env_file}; using ai-worker defaults"
    fi
}

meeting_api_target() {
    local env_type="${1:-local}"
    local target="${VITE_MEETING_API_TARGET:-}"
    local env_file
    env_file="$(env_file_for "${env_type}")"
    if [ -z "${target}" ] && [ -f "${env_file}" ]; then
        set -a
        # shellcheck disable=SC1090
        . "${env_file}"
        set +a
        target="${AI_WORKER_MEETING_API_BASE_URL:-${AI_WORKER_JAVA_API_BASE_URL:-}}"
    fi
    printf '%s\n' "${target:-http://localhost:8080}"
}

pid_running() {
    local pid_file="$1"
    if [ -f "${pid_file}" ]; then
        local pid
        pid="$(cat "${pid_file}")"
        ps -p "${pid}" >/dev/null 2>&1
        return
    fi
    return 1
}

start_api() {
    local env_type="${1:-local}"
    mkdir -p "${RUN_DIR}"
    if pid_running "${API_PID_FILE}"; then
        warn "API already running with PID $(cat "${API_PID_FILE}")"
        return 0
    fi
    command -v uv >/dev/null 2>&1 || { err "uv not found"; exit 1; }

    load_api_env "${env_type}"
    log "Starting API on http://127.0.0.1:${API_PORT}"
    log "API log: ${API_LOG_FILE}"
    cd "${API_DIR}"
    nohup env AI_WORKER_API_PORT="${API_PORT}" uv run ai-worker-api > "${API_LOG_FILE}" 2>&1 &
    local pid=$!
    echo "${pid}" > "${API_PID_FILE}"
    sleep 2
    if ps -p "${pid}" >/dev/null 2>&1; then
        log "API started with PID ${pid}"
    else
        err "API failed to start. Last log lines:"
        tail -n 30 "${API_LOG_FILE}" >&2 || true
        rm -f "${API_PID_FILE}"
        exit 1
    fi
}

start_web() {
    local env_type="${1:-local}"
    mkdir -p "${RUN_DIR}"
    if pid_running "${WEB_PID_FILE}"; then
        warn "Web already running with PID $(cat "${WEB_PID_FILE}")"
        return 0
    fi
    [ -d "${WEB_DIR}" ] || { err "web dir not found: ${WEB_DIR}"; exit 1; }
    [ -d "${WEB_DIR}/node_modules" ] || {
        err "missing ${WEB_DIR}/node_modules; run: cd apps/ai-worker-web && npm ci"
        exit 1
    }

    local target
    target="$(meeting_api_target "${env_type}")"
    log "Starting web on http://${WEB_HOST}:${WEB_PORT}/workstation/"
    log "Proxy /api -> ${target}"
    log "Web log: ${WEB_LOG_FILE}"
    cd "${WEB_DIR}"
    nohup env VITE_MEETING_API_TARGET="${target}" npm run dev -- --host "${WEB_HOST}" --port "${WEB_PORT}" --strictPort > "${WEB_LOG_FILE}" 2>&1 &
    local pid=$!
    echo "${pid}" > "${WEB_PID_FILE}"
    sleep 2
    if ps -p "${pid}" >/dev/null 2>&1; then
        log "Web started with PID ${pid}"
    else
        err "Web failed to start. Last log lines:"
        tail -n 30 "${WEB_LOG_FILE}" >&2 || true
        rm -f "${WEB_PID_FILE}"
        exit 1
    fi
}

kill_pid_file() {
    local label="$1"
    local pid_file="$2"
    if pid_running "${pid_file}"; then
        local pid
        pid="$(cat "${pid_file}")"
        log "Stopping ${label} PID ${pid}"
        pkill -TERM -P "${pid}" 2>/dev/null || true
        kill -TERM "${pid}" 2>/dev/null || true
        local timeout=10
        while [ ${timeout} -gt 0 ]; do
            if ! ps -p "${pid}" >/dev/null 2>&1; then
                break
            fi
            sleep 1
            timeout=$((timeout - 1))
        done
        if ps -p "${pid}" >/dev/null 2>&1; then
            warn "${label} did not stop gracefully; sending SIGKILL"
            pkill -KILL -P "${pid}" 2>/dev/null || true
            kill -KILL "${pid}" 2>/dev/null || true
        fi
    else
        warn "${label} is not running"
    fi
    rm -f "${pid_file}"
}

kill_port_listener() {
    local label="$1"
    local port="$2"
    if command -v lsof >/dev/null 2>&1; then
        local pids
        pids="$(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)"
        if [ -n "${pids}" ]; then
            warn "Stopping ${label} process still listening on ${port}: ${pids}"
            # shellcheck disable=SC2086
            kill -TERM ${pids} 2>/dev/null || true
        fi
    fi
}

stop_api() {
    kill_pid_file "API" "${API_PID_FILE}"
    kill_port_listener "API" "${API_PORT}"
}

stop_web() {
    kill_pid_file "web" "${WEB_PID_FILE}"
    kill_port_listener "web" "${WEB_PORT}"
}

status_api() {
    if pid_running "${API_PID_FILE}"; then
        log "API ACTIVE pid=$(cat "${API_PID_FILE}")"
    else
        log "API INACTIVE"
    fi
    if command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"${API_PORT}" -sTCP:LISTEN || true
    fi
    if curl -fsS --connect-timeout 2 "http://127.0.0.1:${API_PORT}/internal/health" >/dev/null 2>&1; then
        log "API health OK"
    else
        warn "API health unavailable"
    fi
    if curl -fsS --connect-timeout 2 "http://127.0.0.1:${API_PORT}/internal/ready" >/dev/null 2>&1; then
        log "API ready OK"
    else
        warn "API ready unavailable or NotReady"
    fi
}

status_web() {
    if pid_running "${WEB_PID_FILE}"; then
        log "Web ACTIVE pid=$(cat "${WEB_PID_FILE}")"
    else
        log "Web INACTIVE"
    fi
    if command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"${WEB_PORT}" -sTCP:LISTEN || true
    fi
    if curl -fsS --connect-timeout 2 "http://127.0.0.1:${WEB_PORT}/workstation/" >/dev/null 2>&1; then
        log "Web HTTP OK"
    else
        warn "Web HTTP unavailable"
    fi
}

tail_logs() {
    local service="${1:-all}"
    mkdir -p "${RUN_DIR}"
    case "${service}" in
        api) touch "${API_LOG_FILE}"; tail -f "${API_LOG_FILE}" ;;
        web) touch "${WEB_LOG_FILE}"; tail -f "${WEB_LOG_FILE}" ;;
        all) touch "${API_LOG_FILE}" "${WEB_LOG_FILE}"; tail -f "${API_LOG_FILE}" "${WEB_LOG_FILE}" ;;
        *) err "invalid service '${service}'"; exit 64 ;;
    esac
}

main() {
    local action="${1:-}"
    shift || true
    if [ -z "${action}" ] || [ "${action}" = "-h" ] || [ "${action}" = "--help" ] || [ "${action}" = "help" ]; then
        usage
        return 0
    fi

    if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ] || [ "${1:-}" = "help" ]; then
        usage
        return 0
    fi

    local service env_type
    service="$(normalize_service "${1:-all}")"
    env_type="$(normalize_env "${1:-all}" "${2:-local}")"

    case "${action}:${service}" in
        start:api) start_api "${env_type}" ;;
        start:web) start_web "${env_type}" ;;
        start:all) start_api "${env_type}"; start_web "${env_type}" ;;
        stop:api) stop_api ;;
        stop:web) stop_web ;;
        stop:all) stop_web; stop_api ;;
        restart:api) stop_api; start_api "${env_type}" ;;
        restart:web) stop_web; start_web "${env_type}" ;;
        restart:all) stop_web; stop_api; start_api "${env_type}"; start_web "${env_type}" ;;
        status:api) status_api ;;
        status:web) status_web ;;
        status:all) status_api; status_web ;;
        logs:api) tail_logs api ;;
        logs:web) tail_logs web ;;
        logs:all) tail_logs all ;;
        *) usage >&2; exit 64 ;;
    esac
}

main "$@"
