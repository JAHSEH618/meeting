#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# meeting-web 停止（停止并删除前端容器）
# 用法：./scripts/meeting-web-stop.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./meeting-web-stop.sh\n' >&2
    exit 64
fi

log() { printf '[meeting-web] %s\n' "$*"; }

log "=== 停止前端 ==="
docker stop meeting-web 2>/dev/null || true
docker rm meeting-web 2>/dev/null || true

log "✅ 前端已停止"
