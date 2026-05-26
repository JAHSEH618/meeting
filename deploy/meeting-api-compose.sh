#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${MEETING_API_COMPOSE_FILE:-${REPO_ROOT}/infra/meeting-infra/docker/compose/docker-compose.yml}"
ENV_FILE="${MEETING_API_ENV_FILE:-${REPO_ROOT}/deploy/.meeting-api-oss.env}"
IMAGE_TAG="${MEETING_API_IMAGE:-meeting-api:dev}"

COMPOSE=(docker compose -f "${COMPOSE_FILE}" --profile full-stack)

log() { printf '[meeting-api] %s\n' "$*"; }
warn() { printf '[meeting-api] WARN: %s\n' "$*" >&2; }

usage() {
    cat <<EOF
Usage: $0 <command> [options]

Commands:
  start [--no-build]      Build meeting-api:dev, then start meeting-api
  stop                    Stop meeting-api only; keep postgres/rabbitmq running
  restart [--no-build]    Build meeting-api:dev, then recreate meeting-api
  build                   Build meeting-api:dev only
  ps                      Show compose service status
  logs                    Follow meeting-api logs

Environment:
  MEETING_API_ENV_FILE      env file to source before compose commands
                            default: deploy/.meeting-api-oss.env
  MEETING_API_IMAGE         image tag to build/use, default: meeting-api:dev
  MEETING_API_COMPOSE_FILE  compose file path
EOF
}

load_env() {
    if [ -f "${ENV_FILE}" ]; then
        log "Loading env from ${ENV_FILE}"
        set -a
        # shellcheck disable=SC1090
        . "${ENV_FILE}"
        set +a
    else
        warn "Env file not found: ${ENV_FILE}; using compose defaults"
    fi
}

build_image() {
    log "Building ${IMAGE_TAG}"
    "${REPO_ROOT}/deploy/meeting-api-java.sh" image "${IMAGE_TAG}"
}

should_build() {
    [ "${1:-}" != "--no-build" ]
}

start_api() {
    load_env
    if should_build "${1:-}"; then
        build_image
    fi
    log "Starting meeting-api"
    "${COMPOSE[@]}" up -d meeting-api
    "${COMPOSE[@]}" ps meeting-api
}

stop_api() {
    load_env
    log "Stopping meeting-api"
    "${COMPOSE[@]}" stop meeting-api
}

restart_api() {
    load_env
    if should_build "${1:-}"; then
        build_image
    fi
    log "Recreating meeting-api"
    "${COMPOSE[@]}" up -d --force-recreate meeting-api
    "${COMPOSE[@]}" ps meeting-api
}

case "${1:-}" in
    start)
        shift
        start_api "${1:-}"
        ;;
    stop)
        shift
        stop_api
        ;;
    restart)
        shift
        restart_api "${1:-}"
        ;;
    build)
        shift
        build_image
        ;;
    ps|status)
        shift
        load_env
        "${COMPOSE[@]}" ps
        ;;
    logs)
        shift
        load_env
        "${COMPOSE[@]}" logs -f --tail 200 meeting-api
        ;;
    -h|--help|help)
        usage
        ;;
    *)
        usage >&2
        exit 64
        ;;
esac
