#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# meeting-api 启动（Docker Compose 全栈：PostgreSQL + RabbitMQ + meeting-api）
# 用法：./scripts/meeting-api-start.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./meeting-api-start.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

COMPOSE_FILE="infra/meeting-infra/docker/compose/docker-compose.yml"
PROD_OVERRIDE="docker-compose.prod.yml"

log() { printf '[meeting-api] %s\n' "$*"; }

log "=== 加载配置 ==="
loaded_env=""
for env_file in deploy/.meeting-api-prod.env deploy/.meeting-api-oss.env; do
    if [ -f "${env_file}" ]; then
        log "source ${env_file}"
        set -a
        # shellcheck disable=SC1090
        . "${env_file}"
        set +a
        loaded_env="${env_file}"
        break
    fi
done
[ -n "${loaded_env}" ] || log "WARN: 未找到 env 文件，使用 compose 默认值 (deploy/.meeting-api-prod.env|.meeting-api-oss.env)"

log "=== 检查 Docker 服务状态 ==="
if ! docker info >/dev/null 2>&1; then
    if command -v systemctl >/dev/null 2>&1; then
        log "Docker 未就绪，尝试 sudo systemctl restart docker ..."
        sudo systemctl restart docker || true
        sleep 5
    fi
    docker info >/dev/null 2>&1 || { log "ERROR: 无法连接 Docker，请先启动 Docker Desktop / dockerd"; exit 1; }
fi

# down -v --remove-orphans 只清理本 compose 项目的容器/卷/编排网络。
# 不要在此添加 host 级的 `docker rm -f $(docker ps -aq)` 或 `docker network prune`，
# 那会误删宿主机上与本项目无关的容器和网络。
log "=== 停止旧实例 ==="
docker compose -f "${COMPOSE_FILE}" -f "${PROD_OVERRIDE}" down -v --remove-orphans 2>/dev/null || true

log "=== 启动 PostgreSQL + RabbitMQ ==="
docker compose -f "${COMPOSE_FILE}" -f "${PROD_OVERRIDE}" up -d postgres rabbitmq

log "=== 等待数据库就绪 (15s) ==="
sleep 15

log "=== 启动 meeting-api ==="
docker compose -f "${COMPOSE_FILE}" -f "${PROD_OVERRIDE}" --profile full-stack up -d meeting-api

log "=== 等待服务启动 (8s) ==="
sleep 8

log "=== 服务状态 ==="
docker ps | grep -E "NAME|meeting-api|postgres|rabbitmq" || true

log "✅ 启动完成！实时日志: docker logs -f meeting-api"
