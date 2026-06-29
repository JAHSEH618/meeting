# meeting-infra

本地与部署基础设施工程。

详细工程规格见 [`SPEC.md`](SPEC.md)。

## 目录结构

```text
docker/compose/          # Docker Compose 本地一键栈
k8s/base/               # K8s 基础清单
k8s/overlays/dev/       # K8s 开发环境覆盖
terraform/              # 云资源定义
scripts/                # 运维脚本
observability/          # Prometheus / Grafana / Loki 看板
```

## 快速启动（本地开发）

### 前置要求

- Docker Engine 24+ + Docker Compose v2
- 至少 4GB 可用内存（PostgreSQL + pgvector + RabbitMQ + Vault）

### 1. 启动全栈

```bash
# 从仓库根目录执行（.env.example 在根目录）
cp .env.example .env                    # 按需修改
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
```

### 2. 验证各服务健康状态

```bash
# PostgreSQL 15 + pgvector
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml exec postgres pg_isready
# 预期输出: /var/run/postgresql:5432 - accepting connections

# RabbitMQ Management
curl -s -u meeting:meeting_dev http://localhost:15672/api/overview | jq -r '.rabbitmq_version'
# 预期输出: 3.13.x

# Vault-dev
curl -s http://localhost:8200/v1/sys/health | jq -r '.sealed'
# 预期输出: false
```

### 3. 验证 RabbitMQ Schema（队列 / 交换机 / 绑定）

```bash
curl -s -u meeting:meeting_dev http://localhost:15672/api/exchanges/%2f | jq '.[].name'
# 应包含 meeting.task.exchange、meeting.task.dlx

curl -s -u meeting:meeting_dev http://localhost:15672/api/queues/%2f | jq '.[].name'
# 应包含 audio-cpu-queue、gpu-asr-queue、gpu-diar-queue、gpu-speaker-queue、embed-queue、llm-queue、export-queue
# 以及对应死信队列 *.dlq
```

### 4. 连接信息

| 服务 | 地址 | 凭证（默认） |
|---|---|---|
| PostgreSQL | `localhost:5432` | `meeting` / `meeting_dev` |
| RabbitMQ AMQP | `localhost:5672` | `meeting` / `meeting_dev` |
| RabbitMQ Management | http://localhost:15672 | `meeting` / `meeting_dev` |
| Vault | http://localhost:8200 | `root` (dev mode) |
| Prometheus | http://localhost:9090 | 无 |
| Grafana | http://localhost:3000 | `admin` / `admin` |

RabbitMQ local 用户由 `docker/compose/rabbitmq/definitions.json` seed；如果修改 `.env`
里的 `RABBITMQ_USER` / `RABBITMQ_PASS`，需要同步更新 definitions 里的用户、权限和密码 hash。

### 5. 关闭与清理

```bash
# 保留数据卷（下次启动数据仍在）
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml down

# 完全清理（删除数据卷）
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml down -v
```

## 排障

### PostgreSQL 启动失败

- **症状**: `pgvector/pgvector:pg15` 镜像拉取失败或容器反复重启
- **排查**: `docker logs meeting-postgres`
- **常见原因**: 本地已有其他 PostgreSQL 占用 5432 端口；修改 `.env` 中的 `POSTGRES_PORT`

### RabbitMQ 队列缺失

- **症状**: `/api/queues` 看不到 `audio-cpu-queue` 等队列
- **排查**: `docker logs meeting-rabbitmq`
- **常见原因**: `definitions.json` 未正确挂载；检查 compose 中的 volume 映射

### 端口冲突

若本地已有服务占用默认端口，修改 `.env` 中的对应端口变量后重新 `up -d`。

## 可观测性（可选）

启动时加 `--profile observability` 以同时启动 Prometheus + Grafana：

```bash
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml --profile observability up -d
```

Grafana 默认数据源已配置为 Prometheus；Loki 配置待补充。
