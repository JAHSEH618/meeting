#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ai-worker 停止（本地 uv，Python API/BFF）
# 用法：./scripts/ai-worker-stop.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./ai-worker-stop.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${REPO_ROOT}/apps/ai-worker/scripts/local-control.sh" stop api
