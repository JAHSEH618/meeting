#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# meeting-web 启动（构建镜像并以 Docker 容器运行，nginx 反代 /api -> meeting-api）
# 用法：./scripts/meeting-web-start.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./meeting-web-start.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

NETWORK="${MEETING_WEB_NETWORK:-compose_default}"
PORT="${MEETING_WEB_PORT:-5173}"
IMAGE="${MEETING_WEB_IMAGE:-meeting-web:dev}"

log() { printf '[meeting-web] %s\n' "$*"; }

log "=== 构建前端镜像 ${IMAGE} ==="
docker build -f apps/meeting-web/Dockerfile -t "${IMAGE}" .

log "=== 启动前端容器 ==="
docker rm -f meeting-web 2>/dev/null || true

# 优先挂到 meeting-api 的 compose 网络上以便 /api 反代；网络不存在则回退默认 bridge。
net_args=()
if docker network inspect "${NETWORK}" >/dev/null 2>&1; then
    net_args=(--network "${NETWORK}")
else
    log "WARN: 网络 ${NETWORK} 不存在（meeting-api 未启动？），改用默认网络；/api 反代可能不可用"
fi

docker run -d --name meeting-web "${net_args[@]}" -p "${PORT}:80" "${IMAGE}"

log "=== 等待前端启动 (3s) ==="
sleep 3
docker ps | grep -E "NAME|meeting-web" || true

log "✅ 前端启动完成: http://localhost:${PORT}"
log "实时日志: docker logs -f meeting-web"
