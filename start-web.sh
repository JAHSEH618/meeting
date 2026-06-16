#!/bin/bash
set -e
cd "$(dirname "$0")"

echo "=== 加载配置 ==="
set -a
source deploy/.meeting-api-prod.env
set +a

echo "=== 构建前端镜像 ==="
docker build -f apps/meeting-web/Dockerfile -t meeting-web:dev .

echo "=== 启动前端 ==="
docker rm -f meeting-web 2>/dev/null || true

docker run -d \
  --name meeting-web \
  --network compose_default \
  -p 5173:80 \
  meeting-web:dev

echo ""
echo "=== 等待前端启动 ==="
sleep 3

echo ""
echo "=== 前端状态 ==="
docker ps | grep -E "NAME|meeting-web"

echo ""
echo "✅ 前端启动完成！"
echo ""
echo "访问地址: http://localhost:5173"
echo "查看日志: docker logs -f meeting-web"
