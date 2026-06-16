#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== Docker 容器状态 ==="
docker ps | grep -E "NAME|meeting-api|postgres|rabbitmq"

echo ""
echo "=== API 健康检查 ==="
if curl -fsSL http://localhost:8080/actuator/health 2>/dev/null | jq . 2>/dev/null; then
    echo "✅ API 运行正常"
else
    echo "❌ API 无法访问（可能还在启动中）"
fi
