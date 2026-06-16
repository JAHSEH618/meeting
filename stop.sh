#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 停止所有服务 ==="
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    -f docker-compose.prod.yml down

echo "✅ 所有服务已停止"
