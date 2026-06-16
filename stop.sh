#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 停止所有服务 ==="
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    -f docker-compose.prod.yml down -v 2>/dev/null || true

echo "=== 清理残留容器 ==="
docker rm -f $(docker ps -aq) 2>/dev/null || true

echo "=== 清理 Docker 网络 ==="
docker network prune -f 2>/dev/null || true

echo "✅ 所有服务已停止并清理完成"
