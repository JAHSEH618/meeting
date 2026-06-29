#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ai-worker 启动（联调 / 两机模式：读取 deploy/.ai-worker-apple-silicon.env.centos，
# Python API/BFF 连接远端 CentOS Java）
# 用法：./scripts/ai-worker-centos-start.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./ai-worker-centos-start.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${REPO_ROOT}/apps/ai-worker/scripts/local-control.sh" start api centos
