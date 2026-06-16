#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 停止服务并清理 ==="
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    -f docker-compose.prod.yml down -v 2>/dev/null || true

echo "=== 清理 Docker 网络 ==="
docker network prune -f

echo ""
echo "=== 启动服务 ==="
./start.sh

echo ""
echo "=== 实时日志（按 Ctrl+C 退出）==="
sleep 3
docker logs -f meeting-api
