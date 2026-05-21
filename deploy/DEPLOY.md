# 本地会议智能系统 · 部署指南

## 一、系统架构概览

```
                    ┌──────────────┐
                    │  meeting-web │  React 18 · Vite 5 · nginx
                    │   (port 80)   │  SPA, 反向代理到 API
                    └──────┬───────┘
                           │ /api/*
    ┌──────────────────────┼──────────────────────────────────┐
    │                      ▼                                   │
    │            ┌──────────────────┐                          │
    │            │   meeting-api    │  Java 17 · Spring Boot 3.3
    │            │   (port 8080)    │  REST · SSE · HMAC 回调接收
    │            └──┬──────────┬────┘                          │
    │               │          │                                │
    │        ┌──────▼──┐  ┌───▼────────┐                      │
    │        │PostgreSQL│  │ RabbitMQ   │  处理任务队列          │
    │        │+pgvector │  │ + exchanges│                      │
    │        └──────────┘  └───┬────────┘                      │
    │                          │ task.audio-cpu,...             │
    │               ┌──────────▼────────┐                      │
    │               │    ai-worker       │  Python 3.11 · FastAPI
    │               │    (port 8090)     │  ASR/Diar/Embed/Rerank
    │               └──────┬────────────┘                      │
    │                      │ 回调 PATCH /internal/processing-tasks
    │                      └──────────► meeting-api             │
    │                                                          │
    │   ┌─────────┐  ┌──────────┐  ┌──────────┐              │
    │   │  MinIO   │  │  Vault   │  │ DashScope│              │
    │   │(port9000)│  │(port8200)│  │ (阿里云)  │              │
    │   └─────────┘  └──────────┘  └──────────┘              │
    └──────────────────────────────────────────────────────────┘
```

**三通道通信：**
1. **Java → ai-worker**：RabbitMQ 异步任务 (`meeting.task.exchange`)
2. **ai-worker → Java**：HMAC 签名的 HTTP 回调 (`PATCH /internal/processing-tasks/{taskId}/steps/{stepName}`)
3. **Java → ai-worker**：同步 RAG Rerank (`POST /internal/rerank`)

**HMAC 双密钥：** `AI_WORKER_CALLBACK_HMAC_SECRET` ≠ `AI_WORKER_INTERNAL_API_HMAC_SECRET`，永不共用。

---

## 二、基础设施组件

| 组件 | 本地 (Docker) | 生产 (K8s/AWS) | 用途 |
|------|-------------|----------------|------|
| **PostgreSQL 15 + pgvector** | `pgvector/pgvector:pg15` | RDS PostgreSQL 15.5 + pgvector 扩展 | 业务数据库 + 向量检索 |
| **RabbitMQ 3.13** | `rabbitmq:3.13-management` (quorum queues) | Amazon MQ / 自托管 | 异步任务分发 + DLQ |
| **MinIO / S3** | `minio/minio:RELEASE.2024-05-28` | S3 存储桶 | 音频、制品、导出文件存储 |
| **Vault 1.17** | `hashicorp/vault:1.17` (dev mode) | 生产 Vault 集群 | KMS 信封加密 (speaker embeddings) |
| **LibreOffice** | 内置在 meeting-api 镜像 | 同 | DOCX → PDF 转换 |
| **Prometheus + Grafana** | `--profile observability` | 托管 Prometheus + Grafana | 可观测性 |

### RabbitMQ 队列拓扑

| 队列名 | Routing Key | 消费者 | DLQ TTL |
|--------|------------|--------|---------|
| `audio-cpu-queue` | `task.audio-cpu` | ai-worker (CPU) | 7 天 |
| `gpu-asr-queue` | `task.gpu-asr` | ai-worker (GPU) | 7 天 |
| `gpu-diar-queue` | `task.gpu-diar` | ai-worker (GPU) | 7 天 |
| `gpu-speaker-queue` | `task.gpu-speaker` | ai-worker (GPU) | 7 天 |
| `embed-queue` | `task.embed` | ai-worker (GPU) | 7 天 |
| `llm-queue` | `task.llm` | ai-worker (GPU) | 7 天 |
| `export-queue` | `task.export` | meeting-api 自身 | 7 天 |

> 每个队列都有对应的 `.dlq` 死信队列，7 天 TTL + 10000 条上限。

---

## 三、本地开发环境部署

### 前置要求

- Docker Desktop / Docker Engine 24+
- JDK 17（仅本地开发/编译时需要）
- Node 20（仅本地前端开发时需要）
- Python 3.11 + uv（仅本地 Python 开发时需要）

### 3.1 一键启动

```bash
# 1. 创建环境配置
cp .env.example .env
# → 编辑 .env，填入 DASHSCOPE_API_KEY 等密钥

# 2. 赋予脚本执行权限
chmod +x deploy/deploy.sh

# 3. 启动完整本地环境
./deploy/deploy.sh local

# 4. 验证健康状态
./deploy/deploy.sh health
```

### 3.2 分步启动

```bash
# 第一步：启动基础设施（PostgreSQL, RabbitMQ, MinIO, Vault）
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d

# 第二步：确认基础设施就绪
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml ps
# → 所有容器显示 healthy

# 第三步：启动 meeting-api（需要先构建镜像）
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
  --profile full-stack up -d meeting-api

# 第四步：启动 ai-worker
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
  --profile workstation up -d ai-worker

# 第五步：启动前端开发服务器
cd apps/meeting-web && npm install && npm run dev

# 可选：启动可观测性栈
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
  --profile observability up -d
```

### 3.3 服务端口映射

| 服务 | 端口 | 地址 |
|------|------|------|
| meeting-api | 8080 | http://localhost:8080 |
| ai-worker | 8090 | http://localhost:8090 |
| meeting-web (Vite) | 5173 | http://localhost:5173 |
| PostgreSQL | 5432 | `meeting:meeting_dev@localhost:5432/meeting` |
| RabbitMQ 管理 | 15672 | http://localhost:15672 |
| MinIO API | 9000 | http://localhost:9000 |
| MinIO 控制台 | 9001 | http://localhost:9001 |
| Vault | 8200 | http://localhost:8200 |
| Prometheus | 9090 | http://localhost:9090 |
| Grafana | 3000 | http://localhost:3000 |

### 3.4 常用的本地命令

```bash
# 查看所有服务状态
./deploy/deploy.sh local-status

# 查看特定服务日志
./deploy/deploy.sh logs meeting-api
./deploy/deploy.sh logs ai-worker

# 重启 meeting-api（触发 Flyway 迁移）
./deploy/deploy.sh db-migrate local

# 停止所有服务
./deploy/deploy.sh local-down

# 清理全部（含数据卷！）
./deploy/deploy.sh clean
```

---

## 四、Docker 镜像构建

### 4.1 构建策略

每个 app 有独立的 `Dockerfile` 和 `.dockerignore`：

| 镜像 | 基础镜像 | 构建上下文 | 最终大小(约) |
|------|---------|-----------|------------|
| `meeting-api` | `eclipse-temurin:17-jre-jammy` | `apps/meeting-api/` | ~1.2 GB (含 LibreOffice) |
| `meeting-web` | `nginx:1.27-alpine` | repo root | ~15 MB |
| `ai-worker` (CPU) | `python:3.11-slim` | repo root | ~300 MB |
| `ai-worker` (CUDA) | `nvidia/cuda:12.2.2-cudnn8-runtime-ubuntu22.04` | repo root | ~5 GB |

### 4.2 一键构建

```bash
# 构建所有开发镜像
./deploy/deploy.sh build

# 或手动逐个构建
# meeting-api
docker build -t meeting-api:dev \
  -f apps/meeting-api/Dockerfile \
  apps/meeting-api/

# meeting-web (构建上下文必须是 repo root，用于读取 contracts)
docker build -t meeting-web:dev \
  -f apps/meeting-web/Dockerfile \
  .

# ai-worker (CPU fake 模式)
docker build -t ai-worker:dev \
  -f apps/ai-worker/Dockerfile \
  --build-arg BASE=python:3.11-slim \
  .

# ai-worker (GPU 真实模型) —— Phase J ML: UV_EXTRAS 必须传，否则 ai-worker:cuda
# 不会安装 FlagEmbedding / funasr / pyannote.audio，首个真实任务直接 crash。
docker build -t ai-worker:cuda \
  -f apps/ai-worker/Dockerfile \
  --build-arg UV_EXTRAS=real-models \
  .
```

### 4.3 镜像推送

```bash
# 推送到镜像仓库
./deploy/deploy.sh push registry.example.com/meeting v0.1.0

# 手动示例
docker tag meeting-api:dev registry.example.com/meeting/meeting-api:v0.1.0
docker tag meeting-web:dev registry.example.com/meeting/meeting-web:v0.1.0
docker tag ai-worker:dev registry.example.com/meeting/ai-worker:v0.1.0
docker push registry.example.com/meeting/meeting-api:v0.1.0
docker push registry.example.com/meeting/meeting-web:v0.1.0
docker push registry.example.com/meeting/ai-worker:v0.1.0
```

---

## 五、Kubernetes 部署

### 5.1 K8s 资源清单

```
infra/meeting-infra/k8s/
├── base/
│   ├── kustomization.yaml
│   ├── meeting-api/
│   │   ├── deployment.yaml    # replicas: 2, resources: 500m/1Gi
│   │   └── service.yaml       # ClusterIP:8080 + HPA + PDB + ConfigMap
│   ├── meeting-web/
│   │   └── deployment.yaml    # replicas: 2, nginx:80
│   └── ai-worker/
│       └── statefulset.yaml   # replicas: 1, GPU nodeSelector, /opt/models PVC
├── overlays/
│   ├── dev/
│   │   └── kustomization.yaml # 1 replica, image tag :dev, namespace meeting-dev
│   └── prod/
│       └── kustomization.yaml # 3 replicas, pinned tag, namespace meeting-prod
```

### 5.2 资源规格

| 组件 | 副本 | CPU Request/Limit | Memory Request/Limit | GPU | 存储 |
|------|------|-------------------|---------------------|-----|------|
| meeting-api | 2-6 (HPA) | 500m / 2000m | 1Gi / 2Gi | - | emptyDir |
| meeting-web | 2-3 | 100m / 500m | 64Mi / 256Mi | - | - |
| ai-worker | 1 | 2000m / 4000m | 8Gi / 16Gi | 1 GPU | 5Gi PVC |

### 5.3 部署步骤

```bash
# -- 前置：集群中必须已有以下资源 --
#   1. PostgreSQL (含 pgvector 扩展)
#   2. RabbitMQ (含 definitions.json 中的 exchanges/queues)
#   3. MinIO 或 S3 存储桶
#   4. Ingress Controller (nginx-ingress)
#   5. ai-worker-models PVC (预置模型权重)

# 1. 部署到开发环境
./deploy/deploy.sh k8s-dev

# 2. 部署到生产环境
./deploy/deploy.sh k8s-prod

# 3. 查看状态
./deploy/deploy.sh k8s-status dev
./deploy/deploy.sh k8s-status prod

# 4. 销毁环境
./deploy/deploy.sh k8s-destroy dev
```

### 5.4 手动部署

```bash
# 构建 Kustomize 清单
kustomize build infra/meeting-infra/k8s/overlays/dev > deploy/dev-bundle.yaml
kustomize build infra/meeting-infra/k8s/overlays/prod > deploy/prod-bundle.yaml

# 创建命名空间
kubectl create namespace meeting-dev
kubectl create namespace meeting-prod

# Phase J 重点：ai-worker-secret 不再在 K8s base 里声明 placeholder（避免
# kustomize apply 覆盖运维 / deploy.sh 刚创建好的真实 Secret）。任何环境
# 必须在 kustomize apply 之前创建好它，否则 ai-worker Pod 会停在
# CreateContainerConfigError。
kubectl create secret generic ai-worker-secret \
  -n meeting-dev \
  --from-literal=AI_WORKER_CALLBACK_HMAC_SECRET=<32-byte-secret> \
  --from-literal=AI_WORKER_INTERNAL_API_HMAC_SECRET=<32-byte-secret> \
  --from-literal=AI_WORKER_ADMIN_JWT_SECRET=<32-byte-secret>

# 创建 Secret（生产环境用 SealedSecrets / Vault 替代）
kubectl create secret generic meeting-api-secret \
  -n meeting-dev \
  --from-literal=POSTGRES_USER=meeting \
  --from-literal=POSTGRES_PASSWORD=meeting_dev \
  --from-literal=RABBITMQ_USER=meeting \
  --from-literal=RABBITMQ_PASS=meeting_dev \
  --from-literal=AI_WORKER_CALLBACK_HMAC_SECRET=<32-byte-secret> \
  --from-literal=AI_WORKER_INTERNAL_API_HMAC_SECRET=<32-byte-secret> \
  --from-literal=DASHSCOPE_API_KEY=<key>

kubectl create secret generic meeting-api-config \
  -n meeting-dev \
  --from-literal=POSTGRES_HOST=postgres-service \
  --from-literal=RABBITMQ_HOST=rabbitmq-service \
  --from-literal=MINIO_ENDPOINT=http://minio-service:9000 \
  --from-literal=AI_WORKER_BASE_URL=http://ai-worker:8090

# 应用清单
kubectl apply -f deploy/dev-bundle.yaml

# 等待部署完成
kubectl rollout status deployment/meeting-api -n meeting-dev --timeout=300s
```

### 5.5 ai-worker 特殊要求

- **GPU 节点**: 集群需有 `nvidia.com/gpu.present: "true"` 标签的节点
- **模型卷**: 预创建 `ai-worker-models` PVC 并预置模型权重。挂载路径必须包含版本号子目录，否则 `/internal/ready` 的 checksum guard 无法校验：
  - `/opt/models/bge-m3/v1/`
  - `/opt/models/bge-reranker-v2-m3/v1/`
  - `/opt/models/qwen3-asr-1.7b/v2026.05.1/`
  - `/opt/models/pyannote/v3.1/`
- **环境变量**: 生产环境必须设 `AI_WORKER_USE_FAKE_RUNTIME=false`、`AI_WORKER_USE_FAKE_ASR_RUNTIME=false`、`AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false`、`HF_HUB_OFFLINE=1`、`TRANSFORMERS_OFFLINE=1`
- **镜像构建** (Phase J): Dockerfile 接受 `UV_EXTRAS` build-arg 控制安装哪些可选依赖。常见组合：`real-bge`（仅 embedding + rerank）、`real-asr`（Qwen3-ASR via funasr）、`real-diarization`（pyannote.audio）、`real-models`（单机全 GPU 装载，逗号分隔多个）。CUDA base image 已对齐 `uv.lock`（torch 2.12 + CUDA 13），其他基线需显式 `--build-arg BASE=...`
- **诊断端点** (Phase J): `GET /internal/hardware`（无需 HMAC）输出 torch/CUDA/MPS 可用性、FlagEmbedding/funasr/pyannote 是否安装、每个模型解析到的 device；`POST /internal/models/warmup?capabilities=embedding,rerank,asr,diarization`（或 `=all`）按能力维度预热
- **checksum 校验 (Phase J)**: 为每个模型设 `AI_WORKER_*_EXPECTED_CHECKSUM=sha256:...`。`/internal/ready` 在 hash 不匹配时返回 503，readinessProbe 转 NotReady，kubelet 停止路由流量。本地准备 mock 权重见 `docs/model-registry.md` Staging Fixtures 章节或 `apps/ai-worker/scripts/stage_mock_weights.py`
- **Secret**: `ai-worker-secret` 必须在 kustomize apply 之前创建（dev 由 `deploy.sh k8s-dev` 自动处理；staging/prod 由 SealedSecrets / ExternalSecret 注入）
- **fsGroup**: `securityContext.fsGroup: 1001` 确保 ai-worker 用户 (uid 1001) 可读取模型

### 5.6 Ingress 配置

```yaml
# ai-worker 工作站 Ingress（仅内部 IP）
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ai-worker-workstation
  annotations:
    nginx.ingress.kubernetes.io/whitelist-source-range: "10.0.0.0/8,172.16.0.0/12"
spec:
  ingressClassName: nginx
  tls:
    - hosts: [workstation.meeting.internal]
      secretName: workstation-tls
  rules:
    - host: workstation.meeting.internal
      http:
        paths:
          - path: /admin
            pathType: Prefix
            backend:
              service:
                name: ai-worker
                port:
                  number: 8090
          - path: /workstation
            pathType: Prefix
            backend:
              service:
                name: ai-worker
                port:
                  number: 8090
```

### 5.7 工作站 SPA 路径约定 (Phase J)

- 前端 `vite.config.ts` 设 `base: "/workstation/"`，构建出的 `index.html` 引用 `/workstation/assets/...`。
- `BrowserRouter basename="/workstation"`，所有 SPA 链接（`/meetings`、`/enrollment`）实际落在 `/workstation/meetings` 等路径下；E2E (`apps/ai-worker-web/e2e/*.spec.ts`) 必须用带前缀的 `page.goto("/workstation/...")`。
- FastAPI 用 `SpaStaticFiles` 子类挂载：SPA 路由（无扩展名、不在 `assets/` 下）的 404 回退到 `index.html`，但缺失的 `*.js / *.css / *.ico` 等真实 404 保留——避免浏览器把 HTML 当 JS module 解析后报隐晦错误。
- **登录跳转**优先级：`window.__WORKSTATION_CONFIG__.authLoginUrl`（运行时，由 `AI_WORKER_AUTH_LOGIN_URL` 注入）→ `VITE_AUTH_LOGIN_URL`（构建期）→ `/auth/login`（同主机兜底）。K8s 多 host 部署设置 `AI_WORKER_AUTH_LOGIN_URL` 即可，无需重 build SPA 镜像。

---

## 六、数据库迁移

### 6.1 自动迁移

`meeting-api` 启动时自动通过 **Flyway** 执行迁移：

```
meeting-api-infrastructure/src/main/resources/db/migration/
└── V{yyyyMMddHHmm}__desc.sql   # Flyway 迁移脚本
```

### 6.2 手动执行迁移

```bash
# 方式 1：重启 meeting-api
docker restart meeting-api

# 方式 2：直接在 PostgreSQL 执行
psql -h localhost -U meeting -d meeting \
  -v ON_ERROR_STOP=1 \
  -f apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V202501010000__initial_schema.sql
```

### 6.3 迁移前检查

```bash
# CI 中的 ddl-check job 会验证每个迁移脚本语法
psql -h localhost -U meeting -d meeting -v ON_ERROR_STOP=1 -f <migration_file>.sql
```

---

## 七、Terraform 云基础架构

### 7.1 创建的 AWS 资源

```hcl
# Terraform 规划三件套：
resource "aws_db_instance"       # RDS PostgreSQL 15.5, 加密存储
resource "aws_s3_bucket"         # S3 导出桶, KMS 加密, 版本控制
resource "aws_kms_key"           # KMS 主密钥, 自动轮转 (30 天)
```

### 7.2 执行命令

```bash
# 规划变更
./deploy/deploy.sh terraform-plan dev
./deploy/deploy.sh terraform-plan prod

# 应用
TF_VAR_db_password="<secure-password>" ./deploy/deploy.sh terraform-apply dev

# 或手动
cd infra/meeting-infra/terraform
terraform init
terraform plan -var="environment=dev" -var="db_password=<password>"
terraform apply -var="environment=dev" -var="db_password=<password>"
```

---

## 八、合约代码生成

当修改 `packages/meeting-contracts/` 中的 OpenAPI/JSON Schema 后：

```bash
# 一键生成所有语言代码
./deploy/deploy.sh codegen

# 或分步执行
cd packages/meeting-contracts
npm install
npm run check                              # 先校验合约一致性
npm run codegen                            # 生成 TS/Python/Java 类型
npm run codegen:check-temp                 # 零侵入 diff 检查
```

生成目标：
| 合约 | 输出 |
|------|------|
| `openapi/public-api.yaml` | `apps/meeting-web/src/shared/api/types.gen.ts` (TS)<br>`apps/meeting-api/.../generated/public-api/` (Java) |
| `openapi/internal-callback-api.yaml` | `apps/ai-worker/ai_worker/generated/internal_callback_types.py` (Python) |
| `openapi/ai-worker-internal-api.yaml` | `apps/ai-worker/ai_worker/generated/ai_worker_internal_types.py` (Python)<br>`apps/meeting-api/.../generated/ai-worker-internal/` (Java) |
| `schemas/rabbitmq/processing-task-message.schema.json` | `apps/ai-worker/ai_worker/generated/processing_task_message.py` (Python) |

---

## 九、环境变量完整清单

<details>
<summary><b>root .env.example</b></summary>

```bash
# PostgreSQL
POSTGRES_DB=meeting
POSTGRES_USER=meeting
POSTGRES_PASSWORD=meeting_dev
POSTGRES_PORT=5432

# RabbitMQ
RABBITMQ_USER=meeting
RABBITMQ_PASS=meeting_dev
RABBITMQ_PORT=5672
RABBITMQ_MGMT_PORT=15672

# MinIO
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001

# Vault
VAULT_DEV_ROOT_TOKEN=meeting-dev-root-token
VAULT_PORT=8200

# DashScope LLM
DASHSCOPE_API_KEY=sk-replace-with-real-key
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1

# HMAC (两个密钥必须不同)
AI_WORKER_CALLBACK_HMAC_SECRET=change-me-callback-secret-32bytes
AI_WORKER_INTERNAL_API_HMAC_SECRET=change-me-internal-secret-32bytes

# 存储 (minio 或 tos)
STORAGE_TYPE=minio

# AI Worker 地址
AI_WORKER_BASE_URL=http://localhost:8090
AI_WORKER_MEETING_API_BASE_URL=http://localhost:8080

# Chunk 策略
CHUNK_STRATEGY_VERSION=v1

# Grafana
GRAFANA_USER=admin
GRAFANA_PASS=admin
GRAFANA_PORT=3000

# Prometheus
PROMETHEUS_PORT=9090
```
</details>

<details>
<summary><b>meeting-api application.yml</b></summary>

```yaml
# 核心配置在 application.yml 中
# 数据库
spring.datasource.url: jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}
spring.datasource.username: ${POSTGRES_USER}
spring.datasource.password: ${POSTGRES_PASSWORD}

# RabbitMQ
spring.rabbitmq.host: ${RABBITMQ_HOST}
spring.rabbitmq.port: ${RABBITMQ_PORT}
spring.rabbitmq.username: ${RABBITMQ_USER}
spring.rabbitmq.password: ${RABBITMQ_PASS}

# 业务配置
meeting.security.callback.hmac-secret: ${AI_WORKER_CALLBACK_HMAC_SECRET}
meeting.security.ai-worker.hmac-secret: ${AI_WORKER_INTERNAL_API_HMAC_SECRET}
meeting.security.ai-worker.base-url: ${AI_WORKER_BASE_URL}
meeting.storage.type: ${STORAGE_TYPE}
meeting.chunk.strategy-version: ${CHUNK_STRATEGY_VERSION}
meeting.llm.dashscope.api-key: ${DASHSCOPE_API_KEY}

# 任务配置
meeting.task.lease-duration-seconds: 300
meeting.task.max-attempts: 3
meeting.lease-scanner.interval-ms: 30000
meeting.sse.heartbeat-interval-seconds: 15
meeting.outbox.poll-interval-ms: 5000
```
</details>

<details>
<summary><b>ai-worker 环境变量 (前缀 AI_WORKER_)</b></summary>

```bash
# 运行模式
AI_WORKER_USE_FAKE_RUNTIME=true       # 开发/测试模式，使用 fake 替代真实模型
AI_WORKER_USE_FAKE_ASR_RUNTIME=true   # 独立控制 ASR 是否 fake
AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=true

# RabbitMQ
AI_WORKER_RABBITMQ_HOST=rabbitmq
AI_WORKER_RABBITMQ_PORT=5672
AI_WORKER_RABBITMQ_USERNAME=meeting
AI_WORKER_RABBITMQ_PASSWORD=meeting_dev

# Callback
AI_WORKER_MEETING_API_BASE_URL=http://meeting-api:8080
AI_WORKER_JAVA_API_BASE_URL=http://meeting-api:8080
AI_WORKER_CALLBACK_HMAC_SECRET=<callback-secret>
AI_WORKER_INTERNAL_API_HMAC_SECRET=<internal-secret>

# 模型 (Phase J — 路径必须带版本子目录，与 checksum 校验保持一致)
AI_WORKER_BGE_M3_MODELS_DIR=/opt/models/bge-m3/v1
AI_WORKER_BGE_RERANKER_MODELS_DIR=/opt/models/bge-reranker-v2-m3/v1
AI_WORKER_QWEN3_ASR_MODELS_DIR=/opt/models/qwen3-asr-1.7b/v2026.05.1
AI_WORKER_PYANNOTE_MODELS_DIR=/opt/models/pyannote/v3.1
AI_WORKER_MODEL_DEVICE=auto            # auto -> cuda > mps > cpu (全局兜底)

# Phase J ML 强化 —— 单模型 device 覆盖（缺省 auto 走全局）。
# 适用场景：单卡 NVIDIA 想让 ASR/DIAR 串行用 cuda:0、embedding/rerank 用 cpu；
# 或者 Mac 开发机想强制 MPS / 强制 CPU 排查 fp16 数值问题。
AI_WORKER_BGE_M3_DEVICE=auto
AI_WORKER_BGE_RERANKER_DEVICE=auto
AI_WORKER_ASR_DEVICE=auto
AI_WORKER_DIARIZATION_DEVICE=auto

# Phase J ML 强化 —— dtype 覆盖。auto 策略：CUDA -> fp16, MPS/CPU -> fp32。
# 仅在 CUDA 上启用 fp16 是因为 MPS 上 fp16 部分算子（norm/softmax 变体）数值
# 不稳定，参考 PyTorch MPS 文档。允许值：auto / fp16 / fp32（bf16 暂不支持
# —— FlagEmbedding 只暴露 use_fp16 开关，没有真实 bf16 通路，未知值会显式 raise
# 而非静默退化为 fp32）。
AI_WORKER_BGE_M3_DTYPE=auto
AI_WORKER_BGE_RERANKER_DTYPE=auto

# Phase J — 模型权重 checksum guard。设置后 /internal/models 与 /internal/ready
# 会比对真实 sha256；不匹配 -> ai-worker readinessProbe 转 NotReady，
# /internal/health 不受影响（避免无限重启 liveness 循环）。
# 值由 apps/ai-worker/scripts/stage_mock_weights.py 输出或基础设施 owner
# 上传真实权重后回填。
AI_WORKER_BGE_M3_EXPECTED_CHECKSUM=sha256:...
AI_WORKER_BGE_RERANKER_EXPECTED_CHECKSUM=sha256:...
AI_WORKER_QWEN3_ASR_EXPECTED_CHECKSUM=sha256:...
AI_WORKER_PYANNOTE_EXPECTED_CHECKSUM=sha256:...

# Phase J — 工作站 SPA 运行时配置。值会被 ai-worker 在
# GET /workstation/runtime-config.json 由 main.tsx 在 bootstrap 时 fetch 并
# 写入 window.__WORKSTATION_CONFIG__，优先级高于前端构建期的 VITE_AUTH_LOGIN_URL。
# 优先级高于前端构建期的 VITE_AUTH_LOGIN_URL。当 K8s Ingress 只把 /admin
# 和 /workstation 路由到 ai-worker、Java 登录在另一台 host 时必须设置。
AI_WORKER_AUTH_LOGIN_URL=https://meeting-api.internal/auth/login

# Admin 面板 JWT
AI_WORKER_ADMIN_JWT_SECRET=<32+-byte-secret>
AI_WORKER_ADMIN_JWT_AUDIENCE=ai-worker-admin
AI_WORKER_ADMIN_JWT_ISSUER=meeting-api
AI_WORKER_ADMIN_JWT_REQUIRED_ROLE=ADMIN
AI_WORKER_ADMIN_UI_DIST_PATH=/app/admin-ui
```
</details>

---

## 十、健康检查与监控

### 10.1 健康端点

| 服务 | 端点 | 用途 |
|------|------|------|
| meeting-api | `GET /actuator/health` | K8s liveness probe |
| meeting-api | `GET /actuator/health/readiness` | K8s readiness probe |
| meeting-api | `GET /actuator/prometheus` | Prometheus metrics |
| meeting-web | `GET /healthz` | Nginx 健康检查 |
| ai-worker | `GET /internal/health` | K8s livenessProbe（不看模型状态） |
| ai-worker | `GET /internal/ready` | K8s readinessProbe（Phase J，触发模型 checksum guard，503 ⇒ NotReady） |
| ai-worker | `GET /metrics` | Prometheus metrics |
| ai-worker | `GET /workstation/runtime-config.json` | 由 SPA bootstrap 拉取，注入 `window.__WORKSTATION_CONFIG__`（来自 `AI_WORKER_AUTH_LOGIN_URL` 等）

### 10.2 一键健康检查

```bash
./deploy/deploy.sh health
```

`health` 现在用 4 项关键探针的退出码表达失败：`meeting-api liveness/readiness` + `ai-worker liveness/ready`，任一项失败整个命令 `exit 1`。`meeting-web` 走 `${WEB_URL}` 仅做观察性检查（dev 模式 web 单独跑 Vite 时不算 fail）。

### 10.3 关键监控指标

```
# meeting-api (Micrometer → Prometheus)
jvm_memory_used_bytes
http_server_requests_seconds_count{uri,status}
meeting_task_status_total{status,type}
meeting_outbox_pending_count
hikaricp_connections_active

# ai-worker (prometheus-client)
worker_task_processed_total{queue,status}
worker_callback_success_total
worker_processing_duration_seconds{step}
worker_embedding_request_duration_seconds
worker_rerank_request_duration_seconds
```

---

## 十一、CI/CD 流水线

### 11.1 GitHub Actions 流水线 (`.github/workflows/ci.yml`)

```
push/PR → 5 并行 jobs:
  1. contracts    — Spectral lint + JSON Schema 校验 + enum 一致性 + fixtures
  2. meeting-api  — mvn verify -q (unit + ArchUnit + Testcontainers IT)
  3. ai-worker    — pyright + pytest + import smoke
  4. meeting-web  — tsc --noEmit + npm test
  5. ddl-check    — 所有 Flyway 迁移脚本语法校验
```

### 11.2 发布流水线（建议扩展）

```yaml
# 发布流程建议
build → test → push-image → deploy-staging → e2e → deploy-prod
```

---

## 十二、故障排查

### 常见问题

| 问题 | 排查思路 |
|------|---------|
| meeting-api 启动失败 | 检查 `docker compose logs meeting-api`；<br>检查 PostgreSQL 是否就绪、Flyway 迁移是否成功 |
| RLS 权限错误 | 应用未设置 `app.tenant_id` 上下文；<br>检查连接池是否正确 `reset tenant context` |
| HMAC 签名验证失败 | 密钥是否一致 (`AI_WORKER_CALLBACK_HMAC_SECRET`)；<br>检查 `signing_string` 是否包含 `/internal` 前缀；<br>时间差是否 <5 分钟 |
| RabbitMQ 消息堆积 | 检查 DLQ (`*-queue.dlq`)；<br>检查 worker 是否在线 (`kubectl get pods`) |
| ai-worker 找不到模型 | 检查 `AI_WORKER_BGE_M3_MODELS_DIR` 路径；<br>PVC 是否挂载、权限是否正确 (uid 1001) |
| pgvector 查询慢 | 检查是否创建了 IVFFlat/HNSW 索引；<br>检查 `probes` 参数 |
| 导出失败 | 检查 LibreOffice 二进制路径 (`LIBREOFFICE_BINARY=soffice`)；<br>检查 `/tmp/soffice` 可写 |

### 日志查看

```bash
# Docker Compose 日志
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml logs -f meeting-api

# K8s 日志
kubectl logs -f -n meeting-dev deployment/meeting-api
kubectl logs -f -n meeting-dev statefulset/ai-worker

# 查看 Flyway 迁移日志
kubectl logs -n meeting-dev deployment/meeting-api | grep Flyway
```

---

## 十三、安全检查清单

- [ ] `.env` 中所有密钥已替换为安全随机值 (≥32 bytes)
- [ ] `AI_WORKER_CALLBACK_HMAC_SECRET` ≠ `AI_WORKER_INTERNAL_API_HMAC_SECRET`
- [ ] `DASHSCOPE_API_KEY` 已配置真实密钥
- [ ] K8s Secret 不包含在版本控制中（使用 SealedSecrets 或 Vault）
- [ ] PostgreSQL 启用 SSL 连接
- [ ] MinIO 启用 HTTPS（生产环境）
- [ ] K8s Pod `securityContext.runAsNonRoot: true`
- [ ] K8s Pod `capabilities.drop: ["ALL"]`
- [ ] meeting-web CSP 策略符合安全要求
- [ ] Ingress 已限制 `/admin` 和 `/workstation` 来源 IP
- [ ] GPU 节点已配置 `nodeSelector` + `taint/toleration`
- [ ] `HF_HUB_OFFLINE=1` + `TRANSFORMERS_OFFLINE=1` 已在生产镜像中设置
