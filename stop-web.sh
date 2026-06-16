#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 停止前端 ==="
docker stop meeting-web 2>/dev/null || true
docker rm meeting-web 2>/dev/null || true

echo "✅ 前端已停止"
