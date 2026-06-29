#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# 一键启动全部工程：meeting-api(docker) -> ai-worker(uv) -> ai-worker-web(vite)
#                  -> meeting-web(docker)
# 用法：./scripts/all-start.sh   （无需任何参数）
# 单个工程启动失败不会中断其余工程；末尾汇总退出码。
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./all-start.sh\n' >&2
    exit 64
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '[all] %s\n' "$*"; }

rc=0
for s in meeting-api-start ai-worker-start ai-worker-web-start meeting-web-start; do
    log ">>> ${s}.sh"
    if ! "${SCRIPT_DIR}/${s}.sh"; then
        log "WARN: ${s}.sh 启动失败（继续启动其余工程）"
        rc=1
    fi
done

if [ "${rc}" -eq 0 ]; then
    log "✅ 全部工程已启动"
else
    log "⚠️  部分工程启动失败，请检查上方日志"
fi
exit "${rc}"
