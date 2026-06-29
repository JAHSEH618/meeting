#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ai-worker-web 重启（本地 vite dev，工作站前端）
# 用法：./scripts/ai-worker-web-restart.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./ai-worker-web-restart.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${REPO_ROOT}/apps/ai-worker/scripts/local-control.sh" restart web local
