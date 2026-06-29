#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# 一键重启全部工程（先 all-stop 再 all-start）。
# 用法：./scripts/all-restart.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./all-restart.sh\n' >&2
    exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[all] %s\n' "$*"; }

log "=== 停止全部 ==="
"${SCRIPT_DIR}/all-stop.sh" || true

log "=== 启动全部 ==="
"${SCRIPT_DIR}/all-start.sh"
