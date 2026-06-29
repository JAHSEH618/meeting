#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ai-worker-web 重启（联调 / 两机模式：重新加载联调环境并代理 /api 到远端 Java）
# 用法：./scripts/ai-worker-web-centos-restart.sh   （无需任何参数）
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

if [ "$#" -ne 0 ]; then
    printf 'Usage: ./ai-worker-web-centos-restart.sh\n' >&2
    exit 64
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "${REPO_ROOT}/apps/ai-worker/scripts/local-control.sh" restart web centos
