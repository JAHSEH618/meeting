#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# meeting-api 重启（先 stop 再 start，非阻塞返回）
# 用法：./scripts/meeting-api-restart.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./meeting-api-restart.sh\n' >&2
    exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[meeting-api] %s\n' "$*"; }

log "=== 停止并清理 ==="
"${SCRIPT_DIR}/meeting-api-stop.sh"

log "=== 重新启动 ==="
"${SCRIPT_DIR}/meeting-api-start.sh"

log "✅ 重启完成。实时日志: docker logs -f meeting-api"
