#!/bin/bash
# 前端问题诊断脚本 - 收集所有必要的证据

echo "=== 1. 检查容器状态 ==="
echo "所有运行中的容器："
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

echo ""
echo "=== 2. 检查后端是否启动成功 ==="
echo "后端最后 30 行日志："
docker logs meeting-api --tail 30

echo ""
echo "=== 3. 检查前端容器状态 ==="
echo "前端最后 30 行日志："
docker logs meeting-web --tail 30

echo ""
echo "=== 4. 检查网络连通性 ==="
echo "前端容器内能否解析 meeting-api："
docker exec meeting-web ping -c 2 meeting-api 2>&1 || echo "无法 ping（可能 ping 命令不存在）"

echo ""
echo "前端容器内能否访问后端 API："
docker exec meeting-web wget -O- --timeout=5 http://meeting-api:8080/actuator/health 2>&1 || echo "无法访问后端"

echo ""
echo "=== 5. 检查前端镜像构建时间 ==="
docker images meeting-web:dev --format "table {{.Repository}}\t{{.Tag}}\t{{.CreatedAt}}"

echo ""
echo "=== 6. 检查前端构建内容 ==="
echo "前端容器内的文件列表（前 20 个）："
docker exec meeting-web ls -lh /usr/share/nginx/html | head -20

echo ""
echo "=== 7. 检查前端 nginx 配置 ==="
echo "Nginx 配置文件："
docker exec meeting-web cat /etc/nginx/conf.d/default.conf

echo ""
echo "=== 8. 从宿主机测试后端 API ==="
echo "测试后端健康检查："
curl -s http://localhost:8080/actuator/health | head -20 || echo "无法访问后端"

echo ""
echo "=== 9. 检查 Docker 网络 ==="
docker network inspect compose_default --format '{{range .Containers}}{{.Name}}: {{.IPv4Address}}{{println}}{{end}}'

echo ""
echo "=== 诊断完成 ==="
