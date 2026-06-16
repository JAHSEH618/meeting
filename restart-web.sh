#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 停止前端 ==="
./stop-web.sh

echo ""
echo "=== 启动前端 ==="
./start-web.sh

echo ""
echo "=== 实时日志（按 Ctrl+C 退出）==="
sleep 3
docker logs -f meeting-web
