#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# meeting-api 停止（停止并清理本 compose 项目：meeting-api + PostgreSQL + RabbitMQ）
# 用法：./scripts/meeting-api-stop.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./meeting-api-stop.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

COMPOSE_FILE="infra/meeting-infra/docker/compose/docker-compose.yml"
PROD_OVERRIDE="docker-compose.prod.yml"

log() { printf '[meeting-api] %s\n' "$*"; }

# 加载 env，避免 compose 变量插值产生未定义告警（env 可缺省）。
for env_file in deploy/.meeting-api-prod.env deploy/.meeting-api-oss.env; do
    if [ -f "${env_file}" ]; then
        set -a
        # shellcheck disable=SC1090
        . "${env_file}"
        set +a
        break
    fi
done

log "=== 停止所有服务 ==="
# down -v --remove-orphans 只拆除本项目的容器、卷与孤儿容器。
# 不要在此添加 `docker rm -f $(docker ps -aq)` 或 `docker network prune` —— 那会跨主机
# 误删与本项目无关的容器和网络。
docker compose -f "${COMPOSE_FILE}" -f "${PROD_OVERRIDE}" down -v --remove-orphans 2>/dev/null || true

log "✅ 所有服务已停止并清理完成"
