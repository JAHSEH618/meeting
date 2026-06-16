#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 停止并清理所有服务 ==="
./stop.sh

echo ""
echo "=== 启动服务 ==="
./start.sh

echo ""
echo "=== 实时日志（按 Ctrl+C 退出）==="
sleep 3
docker logs -f meeting-api
