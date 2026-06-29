#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ai-worker-web 启动（本地 vite dev，工作站前端，默认 :5174/workstation/）
# 用法：./scripts/ai-worker-web-start.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./ai-worker-web-start.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${REPO_ROOT}/apps/ai-worker/scripts/local-control.sh" start web local
