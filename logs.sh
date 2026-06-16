#!/bin/bash
set -e
cd "$(dirname "$0")"

SERVICE=${1:-meeting-api}

echo "=== 查看 $SERVICE 日志（按 Ctrl+C 退出）==="
docker logs -f "$SERVICE"
