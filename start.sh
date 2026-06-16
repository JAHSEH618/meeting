#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 加载配置 ==="
set -a
source deploy/.meeting-api-prod.env
set +a

echo "=== 停止旧实例 ==="
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    -f docker-compose.prod.yml down 2>/dev/null || true

echo "=== 启动 PostgreSQL + RabbitMQ ==="
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    -f docker-compose.prod.yml up -d postgres rabbitmq

echo "=== 等待数据库就绪 ==="
sleep 15

echo "=== 启动 meeting-api ==="
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    -f docker-compose.prod.yml --profile full-stack up -d meeting-api

echo "=== 等待服务启动 ==="
sleep 8

echo ""
echo "=== 服务状态 ==="
docker ps | grep -E "NAME|meeting-api|postgres|rabbitmq"

echo ""
echo "✅ 启动完成！"
echo ""
echo "查看日志: ./logs.sh"
echo "查看状态: ./status.sh"
