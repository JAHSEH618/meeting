#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# 一键停止全部工程（按启动相反顺序）。
# 用法：./scripts/all-stop.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./all-stop.sh\n' >&2
    exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[all] %s\n' "$*"; }

rc=0
for s in meeting-web-stop ai-worker-web-stop ai-worker-stop meeting-api-stop; do
    log ">>> ${s}.sh"
    if ! "${SCRIPT_DIR}/${s}.sh"; then
        log "WARN: ${s}.sh 停止失败（继续停止其余工程）"
        rc=1
    fi
done

if [ "${rc}" -eq 0 ]; then
    log "✅ 全部工程已停止"
else
    log "⚠️  部分工程停止失败，请检查上方日志"
fi
exit "${rc}"
