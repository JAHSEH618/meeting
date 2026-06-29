#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ai-worker-web 启动（联调 / 两机模式：vite dev，将 /api 代理到远端 CentOS Java URL）
# 用法：./scripts/ai-worker-web-centos-start.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./ai-worker-web-centos-start.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${REPO_ROOT}/apps/ai-worker/scripts/local-control.sh" start web centos
