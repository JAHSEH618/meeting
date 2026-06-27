#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 停止所有服务 ==="
# down -v --remove-orphans tears down ONLY this compose project's containers,
# volumes, and any orphaned containers on its network. Do NOT add a blanket
# `docker rm -f $(docker ps -aq)` or `docker network prune` here — those wipe
# unrelated containers and networks across the whole host, not just this project.
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    -f docker-compose.prod.yml down -v --remove-orphans 2>/dev/null || true

echo "✅ 所有服务已停止并清理完成"
