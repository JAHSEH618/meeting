#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# meeting-web 重启（先 stop 再 start，非阻塞返回）
# 用法：./scripts/meeting-web-restart.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./meeting-web-restart.sh\n' >&2
    exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[meeting-web] %s\n' "$*"; }

log "=== 停止前端 ==="
"${SCRIPT_DIR}/meeting-web-stop.sh"

log "=== 启动前端 ==="
"${SCRIPT_DIR}/meeting-web-start.sh"

log "✅ 重启完成。实时日志: docker logs -f meeting-web"
