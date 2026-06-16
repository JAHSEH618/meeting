#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 加载配置 ==="
set -a
source deploy/.meeting-api-prod.env
set +a

echo "=== 重启 meeting-api ==="
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    -f docker-compose.prod.yml --profile full-stack restart meeting-api

echo "=== 等待服务启动 ==="
sleep 5

echo "=== 实时日志（按 Ctrl+C 退出）==="
docker logs -f meeting-api
