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

最低要求按你要跑的路径分两类。仓库根目录提供 `.tool-versions`（`asdf` / `mise` / `rtx` / `proto` / `vfox` 通用），切到这些工具会自动锁到下面的版本。

**所有路径必装：**
- Docker Engine 24+。macOS 推荐 Colima（`brew install colima docker docker-compose`，`colima start --cpu 4 --memory 6`）或 Docker Desktop；Linux 直接 `apt-get install docker.io`。
- Git ≥ 2.39。

**编译/测试本地源码（"Java/Python 路径"）：**
- JDK 17（Maven Enforcer 严格要求 `[17,18)`；高版本会被 enforcer 拒绝）。
- Node 20（CI matrix 用的是 Node 20.x；25/26 会因 `@types` 解析变化在 `tsc --noEmit` 上报错）。
- Python 3.11 + [uv](https://docs.astral.sh/uv/)（`ai-worker` 的 `pyproject.toml` 明确 `requires-python = ">=3.11"`）。

**K8s 演练 / Phase J 验收：**
- `kubectl` ≥ 1.29（基线 K8s 版本，见 `.github/workflows/ci.yml` 的 `kubeconform -kubernetes-version 1.29.0`）。
- `kustomize` ≥ 5.0（CLI 形式；`kubectl kustomize` 内置版本通常滞后，且不支持 `--enable-helm`，本仓库的 dev/prod overlay 暂未引用 helm chart，但 `deploy/deploy.sh:321` 已传该参数，缺它会在引入 helm chart 后突然报错）。
- `helm` ≥ 3.x —— §5.3.1 / §5.3.2 的依赖服务安装命令全部依赖它。安装后一次性初始化所需的 chart 仓库（缺这一步直接 `helm install` 会报 `Error: repo "bitnami" not found`）：

  ```bash
  helm repo add bitnami https://charts.bitnami.com/bitnami
  helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
  helm repo add jetstack https://charts.jetstack.io
  helm repo update
  ```
- `kind` 或 `minikube`（J6 在本机起 K8s 集群）。
- `kubeconform` ≥ 0.6.7（CI 用它做 manifest 校验；本地用同一个工具能避免 schema 抖动）。

**ai-worker 真实模型（GPU 生产路径）：**
- NVIDIA GPU host：CUDA 13.x runtime + `nvidia-container-toolkit`（`nvidia-ctk runtime configure --runtime=docker`），通过 `docker run --gpus all nvidia/cuda:13.0.0-base-ubuntu22.04 nvidia-smi` 验证。
- 模型权重已落盘到 `/opt/models/<model>/<version>/`（详见 §五 5.6）。

> macOS / 无 GPU 机器只能跑 fake runtime 模式（`AI_WORKER_USE_FAKE_*_RUNTIME=true`）。Apple Silicon 上的 MPS 支持仅作为开发期 sanity-check 通道（embedding / rerank 走 fp32），不要拿它做生产推理。

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

# 第五步：启动前端开发服务器（仓库 check in 了 package-lock.json，
# 与 deploy.sh codegen 的 `npm ci` 保持一致：首次 clone 先运行
# `npm ci`，之后日常迭代 `npm run dev` 即可，不需要每次重装依赖）
cd apps/meeting-web && npm ci && npm run dev

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
| `meeting-web` | `nginx:1.27-alpine` | **repo root**（Dockerfile 通过 `apps/meeting-web/...` 引用源码，codegen 直接 COPY `packages/meeting-contracts/`） | ~15 MB |
| `ai-worker` (CPU / fake runtime) | `python:3.11-slim` | repo root | ~300 MB |
| `ai-worker` (CUDA / 真实模型) | `nvidia/cuda:13.0.0-cudnn-runtime-ubuntu22.04`（Dockerfile 默认 BASE，CUDA 13 对齐 `uv.lock` 里的 torch 2.12 wheel；旧 12.x 已弃用） | repo root | ~5 GB |

### 4.2 一键构建

```bash
# 构建所有开发镜像
./deploy/deploy.sh build

# 或手动逐个构建
# meeting-api
docker build -t meeting-api:dev \
  -f apps/meeting-api/Dockerfile \
  apps/meeting-api/

# meeting-web —— 构建上下文必须是 repo root：Dockerfile 内 COPY
# 路径已经写成 `apps/meeting-web/...` 并把 `packages/meeting-contracts/`
# 一并拉进 build stage（`npm run codegen` 要读 ../../packages/...）。
# 早期版本把 context 设成 apps/meeting-web/ 会缺 contracts，build 会
# 在 codegen 步骤报 `ENOENT ../../packages/...`。
docker build -t meeting-web:dev \
  -f apps/meeting-web/Dockerfile \
  .

# ai-worker (CPU / fake runtime) — 本地开发 / macOS 默认走这条
docker build -t ai-worker:dev \
  -f apps/ai-worker/Dockerfile \
  --build-arg BASE=python:3.11-slim \
  .

# ai-worker (CUDA / 真实模型) —— Phase J ML 必须传 UV_EXTRAS，
# 否则 ai-worker:cuda 不会安装 FlagEmbedding / funasr / pyannote.audio，
# 首个真实任务直接 crash。BASE 已对齐 uv.lock（torch 2.12 + CUDA 13），
# 不传 BASE 也是 OK 的；只在用其他 CUDA 主线时显式覆盖。
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

# Phase J / 生产 ai-worker 走 CUDA 镜像：prod overlay 里 image tag
# 是 `ai-worker:cuda-v0.1.0`（见 infra/meeting-infra/k8s/overlays/prod/
# kustomization.yaml），与上面 dev 用的 `ai-worker:v0.1.0` 是两个不
# 同 tag。漏推这条会让 prod Pod 直接 ImagePullBackOff。
docker tag ai-worker:cuda registry.example.com/meeting/ai-worker:cuda-v0.1.0
docker push registry.example.com/meeting/ai-worker:cuda-v0.1.0
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

### 5.3 部署前提：依赖服务必须已存在

**`infra/meeting-infra/k8s/` 只包含应用层清单**（meeting-api / meeting-web / ai-worker）。
PostgreSQL、RabbitMQ、对象存储、Ingress 控制器、模型 PVC 都不在 base 里，必须在 apply
overlay **之前**先准备好。CI 的 `kubeconform` 检查不会发现依赖缺失，只会在 Pod
启动时表现为 `CrashLoopBackOff: Connection refused`。

#### 5.3.1 集群级控制面（每集群一次）

> 下面的 helm 命令假设三个 chart 仓库已添加（`bitnami` / `ingress-nginx` / `jetstack`）。若本机首次运行，先按 §二 的 K8s 工具清单执行 `helm repo add ... && helm repo update`，否则会报 `Error: repo "..." not found`。所有命令都已改成 `helm upgrade --install`，可幂等重跑。

| 组件 | 安装方式 | 验证命令 |
|------|---------|---------|
| Ingress Controller | `helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx -n ingress-nginx --create-namespace`（kind 用 `kind-ingress-nginx` 预设） | `kubectl -n ingress-nginx get svc ingress-nginx-controller` 有 `EXTERNAL-IP`（或 NodePort） |
| StorageClass | EKS/GKE/AKS 内置；kind 用 `local-path-provisioner`（默认开启） | `kubectl get sc` 显示 `(default)` |
| Cert-manager（仅生产 TLS） | `helm upgrade --install cert-manager jetstack/cert-manager -n cert-manager --create-namespace --set installCRDs=true` | `kubectl -n cert-manager get pods` 全 `Ready` |

#### 5.3.2 命名空间内依赖服务（每环境一次）

容器化部署用 Bitnami helm charts 起；托管服务（RDS / Amazon MQ / S3 / OSS）则在
overlay 的 `meeting-api-config` ConfigMap 里覆盖 `POSTGRES_HOST` / `RABBITMQ_HOST` /
`MINIO_ENDPOINT` 指向托管 endpoint。

```bash
NS=meeting-dev   # 或 meeting-prod
# namespace / secret / configmap 全部走 `kubectl create ... --dry-run=client
# -o yaml | kubectl apply -f -` 的幂等模式：第一次创建、再跑时变成 apply，
# 不会再因 "AlreadyExists" 中断部署演练。
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

# PostgreSQL + pgvector —— Bitnami chart 默认装的是无 pgvector 的镜像，
# 必须把 image 指到 pgvector/pgvector，并在 init script 里 CREATE EXTENSION。
# fullnameOverride=postgres 让 svc 名变成 `postgres`，对齐
# meeting-api-config 的默认 POSTGRES_HOST（缺省 Bitnami 命名是
# `<release>-postgresql`，会导致 meeting-api 在集群里解析不到主机名）。
# `helm upgrade --install` 取代 `helm install`：首次执行等价于 install，
# 后续重跑直接走 upgrade，部署演练可反复执行。
helm upgrade --install postgres bitnami/postgresql -n "$NS" \
  --set fullnameOverride=postgres \
  --set image.registry=docker.io \
  --set image.repository=pgvector/pgvector \
  --set image.tag=pg15 \
  --set auth.username=meeting \
  --set auth.password=meeting_dev \
  --set auth.database=meeting \
  --set primary.initdb.scripts."enable-pgvector\.sql"="CREATE EXTENSION IF NOT EXISTS vector;"

# RabbitMQ —— 启用 quorum queues（meeting-api 的 outbox 依赖），
# 把 definitions.json 通过 Secret 注入。Bitnami chart 的
# loadDefinition.existingSecret 期望的是 Secret（不是 ConfigMap），
# 且 chart 里硬编码读 key `load_definition.json`（snake_case，不要
# 误写成 definitions.json）。fullnameOverride=rabbitmq 对齐 base
# ConfigMap 的 RABBITMQ_HOST 默认值。
# auth.securePassword=false 是 Bitnami 自身的兼容性要求：默认 true 时
# chart 会强制生成 32 字符以上的强随机密码，与 loadDefinition 注入
# 的 definitions.json 内置用户/密码不匹配，导致启动后用户加载不全。
# 参考：bitnami/rabbitmq values.yaml `loadDefinition` 段说明。
kubectl -n "$NS" create secret generic rabbitmq-definitions \
  --from-file=load_definition.json=infra/meeting-infra/docker/compose/rabbitmq/definitions.json \
  --dry-run=client -o yaml | kubectl apply -f -
helm upgrade --install rabbitmq bitnami/rabbitmq -n "$NS" \
  --set fullnameOverride=rabbitmq \
  --set auth.username=meeting --set auth.password=meeting_dev \
  --set auth.securePassword=false \
  --set loadDefinition.enabled=true \
  --set loadDefinition.existingSecret=rabbitmq-definitions

# MinIO —— 简化为单副本；fullnameOverride=minio 让 svc DNS 与
# base ConfigMap 默认 MINIO_ENDPOINT=http://minio:9000 对齐。
# 生产环境改成 S3/OSS 后在 overlay 里覆盖 STORAGE_TYPE=oss +
# OSS_ENDPOINT/OSS_REGION，不再装 chart。
helm upgrade --install minio bitnami/minio -n "$NS" \
  --set fullnameOverride=minio \
  --set auth.rootUser=minioadmin --set auth.rootPassword=minioadmin \
  --set defaultBuckets="meeting-audio-auska meeting-artifacts meeting-exports"

# ai-worker 模型 PVC —— 名称与挂载 path 必须与 base/ai-worker/statefulset.yaml 完全一致
kubectl -n "$NS" apply -f - <<EOF
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: ai-worker-models
spec:
  accessModes: [ReadOnlyMany]
  resources:
    requests:
      storage: 50Gi
EOF
# 把权重灌入 PVC 的方式见 §5.6 + apps/ai-worker/scripts/stage_mock_weights.py
```

#### 5.3.3 命名空间内 DNS 约定（base ConfigMap 默认值）

| ConfigMap key | 默认值 | 在 kind/minikube/helm 默认安装下解析到 |
|---------------|--------|-----------------------------------|
| `POSTGRES_HOST` | `postgres` | `postgres.<ns>.svc.cluster.local`（helm install 名 `postgres` 时；Bitnami 默认 service 名是 `postgres-postgresql`，覆盖一下：`--set fullnameOverride=postgres`） |
| `RABBITMQ_HOST` | `rabbitmq` | 同上，`--set fullnameOverride=rabbitmq` |
| `MINIO_ENDPOINT` / `MEETING_STORAGE_ENDPOINT` | `http://minio:9000` | `http://minio.<ns>.svc.cluster.local:9000`，Bitnami 用 `--set fullnameOverride=minio` |
| `AI_WORKER_BASE_URL` | `http://ai-worker:8090` | base/ai-worker 创建的 `ai-worker` Service |

托管服务覆盖示例：

```yaml
# overlays/prod/kustomization.yaml 中追加
patches:
  - target: { kind: ConfigMap, name: meeting-api-config }
    patch: |
      - op: replace
        path: /data/POSTGRES_HOST
        value: "meeting-prod.cluster-xxx.us-east-1.rds.amazonaws.com"
      - op: replace
        path: /data/RABBITMQ_HOST
        value: "b-xxx.mq.us-east-1.amazonaws.com"
      - op: replace
        path: /data/STORAGE_TYPE
        value: "oss"
      - op: add
        path: /data/OSS_ENDPOINT
        value: "https://oss-cn-hangzhou-internal.aliyuncs.com"
```

### 5.4 命名空间约定

deploy.sh 与 overlay 都按 `meeting-${env}` 命名：

| Overlay | namespace | 用途 |
|---------|-----------|------|
| `overlays/dev/` | `meeting-dev` | kind/minikube 本地集群、Phase J J1/J6 验收（当前的 acceptance 环境）|
| `overlays/staging/` | _TBD_（占位目录，文件待补） | 预留 — staging overlay 未落地前，所有 "staging" 措辞（包括 Phase J runbook、§11.2 发布流水线）一律落到 `meeting-dev` |
| `overlays/prod/` | `meeting-prod` | 生产 |

如果 runbook 旧版本提到 `meeting-staging`，请按当前 overlay 实际值替换为 `meeting-dev`。
staging overlay 文件正式落地后，请同步更新本表、`infra/meeting-infra/k8s/README.md`
与 `docs/runbooks/phase-j-acceptance.md`，避免三个文档对 acceptance 环境出现分歧。

### 5.5 部署步骤（应用层）

> §5.3.2 的依赖服务安装已经收口进 `./deploy/deploy.sh k8s-deps <env>`，
> 命名空间 + PostgreSQL+pgvector + RabbitMQ（含 securePassword 修正 +
> loadDefinition Secret）+ MinIO 都靠它一条命令拉起来。任何手动 helm
> install 必须保持与该函数同步。

```bash
# 1. 准备命名空间内依赖（每环境一次，等价于手抄 §5.3.2 的 helm 块）
./deploy/deploy.sh k8s-deps dev

# 2. 部署应用层到开发环境
./deploy/deploy.sh k8s-dev

# 3. 生产环境
# 推荐使用托管 RDS / MQ / S3，并在 prod overlay + ExternalSecret 中覆盖
# POSTGRES_HOST / RABBITMQ_HOST / STORAGE_TYPE / OSS_*。只有明确决定把依赖
# 也部署到集群内时，才显式放开下面的命令：
ALLOW_IN_CLUSTER_PROD_DEPS=1 \
POSTGRES_PASSWORD="<strong-password>" \
RABBITMQ_PASS="<strong-password>" \
MINIO_ROOT_PASSWORD="<strong-password>" \
  ./deploy/deploy.sh k8s-deps prod
./deploy/deploy.sh k8s-prod

# 4. 查看状态
./deploy/deploy.sh k8s-status dev
./deploy/deploy.sh k8s-status prod

# 5. 销毁环境
./deploy/deploy.sh k8s-destroy dev
```

### 5.6 手动部署 (`deploy.sh` 等价命令)

```bash
NS=meeting-dev

# Phase J 重点：ai-worker-secret 不再在 K8s base 里声明 placeholder（避免
# kustomize apply 覆盖运维 / deploy.sh 刚创建好的真实 Secret）。任何环境
# 必须在 kustomize apply 之前创建好它，否则 ai-worker Pod 会停在
# CreateContainerConfigError。两个 secret 都走 `kubectl create ... |
# kubectl apply -f -` 幂等模式，与 deploy.sh:281 的写法保持一致，方便
# 部署演练反复执行。
kubectl create secret generic ai-worker-secret \
  -n "$NS" \
  --from-literal=AI_WORKER_CALLBACK_HMAC_SECRET=<32-byte-secret> \
  --from-literal=AI_WORKER_INTERNAL_API_HMAC_SECRET=<32-byte-secret> \
  --from-literal=AI_WORKER_ADMIN_JWT_SECRET=<32-byte-secret> \
  --dry-run=client -o yaml | kubectl apply -f -

# meeting-api-secret —— 生产用 SealedSecrets / Vault / ExternalSecret 替代
kubectl create secret generic meeting-api-secret \
  -n "$NS" \
  --from-literal=POSTGRES_USER=meeting \
  --from-literal=POSTGRES_PASSWORD=meeting_dev \
  --from-literal=RABBITMQ_USER=meeting \
  --from-literal=RABBITMQ_PASS=meeting_dev \
  --from-literal=AI_WORKER_CALLBACK_HMAC_SECRET=<32-byte-secret> \
  --from-literal=AI_WORKER_INTERNAL_API_HMAC_SECRET=<32-byte-secret> \
  --from-literal=DASHSCOPE_API_KEY=<key> \
  --from-literal=KMS_MASTER_KEY_ID=<kms-key-id> \
  --from-literal=MEETING_KMS_MASTER_KEY_BASE64=<base64-32-bytes> \
  --dry-run=client -o yaml | kubectl apply -f -
# KMS 说明（必读）：
#   * KMS_MASTER_KEY_ID 是 ProdProfileValidator 检查的字段，不能是
#     `dev-kms-master-key`，否则 prod profile 启动直接 fail。
#   * MEETING_KMS_MASTER_KEY_BASE64 是 LocalKmsGateway 实际加解密用的
#     32 字节 AES-256 master key（base64）。不提供时 LocalKmsGateway 会
#     启动时生成随机 key —— 仅限 dev/test，prod 重启即失能（说话人
#     embedding 全部无法解密）。生成方式：`openssl rand -base64 32`。
#   * 切换到云 KMS 后这个 base64 不再需要，但 ID 仍然要求非 demo 值。

# meeting-api-config 是 ConfigMap（不是 Secret），由 kustomize 从
# infra/meeting-infra/k8s/base/meeting-api/service.yaml 直接渲染。
# 默认值已经写好集群内 DNS（postgres / rabbitmq / minio / ai-worker），
# 需要覆盖时在 overlay 里 JSON patch（见 §5.3.3）。不要再用
# `kubectl create configmap meeting-api-config` —— 那是旧文档的写法，
# 会和 kustomize 渲染的同名 ConfigMap 冲突 / 互相覆盖。

# 构建 Kustomize 清单（与 deploy.sh 完全一致：保留 --enable-helm，
# 这样后续引入任何 helm-chart 形式的依赖时不会出现"脚本能跑、手动命令
# 报 helmChart inflator not enabled"的不一致）
kustomize build infra/meeting-infra/k8s/overlays/dev --enable-helm \
    > deploy/.kustomize-dev.yaml
kubectl apply -f deploy/.kustomize-dev.yaml

# 等待部署完成
kubectl rollout status deployment/meeting-api -n "$NS" --timeout=300s
kubectl rollout status deployment/meeting-web -n "$NS" --timeout=300s
kubectl rollout status statefulset/ai-worker  -n "$NS" --timeout=600s
```

### 5.7 meeting-api prod profile 必读

`ProdProfileValidator`（`apps/meeting-api/.../start/config/ProdProfileValidator.java`）只在 `SPRING_PROFILES_ACTIVE=prod` 时启用，启用后会在 Bean 创建阶段 fail-fast。把所有违反项一次列出，避免反复重启排错。

- **触发开关**：prod overlay 已通过 ConfigMap patch 注入 `SPRING_PROFILES_ACTIVE=prod`。dev/staging 不要打这个 flag，否则会被 validator 卡住。
- **HMAC 双密钥**：`AI_WORKER_CALLBACK_HMAC_SECRET` 与 `AI_WORKER_INTERNAL_API_HMAC_SECRET` 必须为非 demo 值，且彼此不同。
- **AI worker base URL**：`AI_WORKER_BASE_URL` 不能含 `localhost` / `127.0.0.1`。
- **KMS key id**：`KMS_MASTER_KEY_ID` 不能是默认的 `dev-kms-master-key`。
- **Flyway baseline**：prod overlay 同时注入 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`，因为 `application.yml` 的 dev-friendly 默认是 `true`，validator 在 prod 拒绝这种隐式 baseline。
- **存储 endpoint**：`MEETING_STORAGE_ENDPOINT` 必须显式设到集群内的 MinIO/S3 URL。`LocalObjectStorageGateway` 读 `meeting.storage.endpoint`（而不是 `meeting.storage.minio.endpoint`）来生成预签名 URL；不设会默认 `http://localhost:9000`，所有上传/下载链接在集群里都不可用。base ConfigMap 已默认 `http://minio:9000`，运行在 S3 上时由 overlay 覆盖。
- **KMS master key**：本地实现 (`LocalKmsGateway`) 通过 `MEETING_KMS_MASTER_KEY_BASE64` 读 32 字节 AES-256 master key（base64）。**不设的话每次启动生成随机 key**，重启即丢，所有已加密的 speaker embedding 都会解不开。生成方法：`openssl rand -base64 32`。切换到云 KMS 后此变量可省略。

### 5.8 ai-worker 特殊要求

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

### 5.9 Ingress 配置

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

### 5.10 工作站 SPA 路径约定 (Phase J)

- 前端 `vite.config.ts` 设 `base: "/workstation/"`，构建出的 `index.html` 引用 `/workstation/assets/...`。
- `BrowserRouter basename="/workstation"`，所有 SPA 链接（`/meetings`、`/enrollment`）实际落在 `/workstation/meetings` 等路径下；E2E (`apps/ai-worker-web/e2e/*.spec.ts`) 必须用带前缀的 `page.goto("/workstation/...")`。
- FastAPI 用 `SpaStaticFiles` 子类挂载：SPA 路由（无扩展名、不在 `assets/` 下）的 404 回退到 `index.html`，但缺失的 `*.js / *.css / *.ico` 等真实 404 保留——避免浏览器把 HTML 当 JS module 解析后报隐晦错误。
- **登录跳转**优先级：`window.__WORKSTATION_CONFIG__.authLoginUrl`（运行时，由 `AI_WORKER_AUTH_LOGIN_URL` 注入）→ `VITE_AUTH_LOGIN_URL`（构建期）→ `/auth/login`（同主机兜底）。K8s 多 host 部署设置 `AI_WORKER_AUTH_LOGIN_URL` 即可，无需重 build SPA 镜像。

---

## 五·五、平台分流：Java + Python 详细部署路径

K8s 章节给出的是「一个 overlay 应用所有平台」的视角。下面按运行平台拆开，列出实际遇到的差异点。表里 ❌ 表示不支持，⚠️ 表示能跑但仅限开发 / smoke。

> **入口脚本（落盘版）**
>
> | 路径 | 用途 | 配套 runbook |
> |------|------|-----|
> | `deploy/meeting-api-java.sh` | Java meeting-api 的 jar / image / compose / k8s / migrate 全流程一站式入口；屏蔽 JDK 17 自动探测、Maven Enforcer 触发、Flyway 三种迁移路径之间的差异。 | `docs/runbooks/meeting-api-java.md` |
> | `deploy/ai-worker-apple-silicon.sh` | Apple Silicon 原生跑 `UV_EXTRAS=real-models`（BGE + Qwen3-ASR + pyannote 全量真实模型）；自动把 BGE 设到 MPS / fp32，ASR + diarization 落到 CPU。 | `docs/runbooks/ai-worker-apple-silicon.md` |
>
> 这两条命令是 Phase J 验收和日常本地开发的默认通道；DEPLOY 文档其他章节里出现的命令都可以视作它们的展开。详细步骤、故障排查、性能预期请看 runbook，本节只列概览。

| 平台 | meeting-api (Java) | ai-worker (Python, fake) | ai-worker (Python, 真实模型) | meeting-web |
|------|-------------------|------------------------|---------------------------|--------------|
| **Linux x86_64 + NVIDIA GPU**（生产） | ✅ JDK 17 / JRE 17 镜像直接跑 | ✅ | ✅ CUDA 13 wheel | ✅ |
| **Linux x86_64 / CPU 服务器**（staging / 应急） | ✅ 同上 | ✅ | ⚠️ 仅 BGE / rerank 走 fp32 CPU；Qwen3-ASR 实测 ≥ 8× 实时延迟，建议下降为 fake-ASR | ✅ |
| **macOS Apple Silicon (arm64)**（开发机） | ✅ 用 Temurin 17 arm64 原生镜像 | ✅ | ✅ 走 `deploy/ai-worker-apple-silicon.sh`：BGE → MPS / fp32，ASR + diarization → CPU。整机吞吐约为单卡 RTX 4080 的 1/10，不要拿到生产 | ✅ |
| **macOS Intel** | ⚠️ Docker 跑得动，性能差；推荐远端开发 | ✅ | ❌ 无 CUDA、CPU 推理太慢 | ✅ |
| **Windows + WSL2** | ✅ 同 Linux x86_64 | ✅ | ✅（NVIDIA GPU pass-through 必须装 `nvidia-container-toolkit` for WSL） | ✅ |

### 5·5.1 Java 路径（meeting-api）

> 一站式入口：`./deploy/meeting-api-java.sh {test|jar|image|compose|k8s|migrate}`。
> 脚本里封装了 JDK 17 自动探测（macOS `java_home -v 17`、`JAVA_HOME` 校验）、
> Apple Silicon 上的 `buildx --platform linux/amd64` 跨架构镜像构建、
> Flyway 三种迁移路径（rollout restart / docker / psql `ON_ERROR_STOP`）。
> 下面四小节是分解视图，方便定位单一步骤。

#### A. 直接跑 jar（最快验收启动逻辑）

```bash
# 所有平台通用 —— 仅依赖 JDK 17
cd apps/meeting-api
JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo /usr/lib/jvm/temurin-17-jdk-amd64) \
  ./mvnw -pl meeting-api-start -am -DskipTests package
java -jar meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar
```

需要把 `application.yml` 里所有 `${...}` 占位变量从 shell 传进去；最小集见 §9 `meeting-api application.yml`。本机起 Postgres / RabbitMQ / MinIO 用 `docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d`。

#### B. Docker（生产唯一推荐）

```bash
# 所有平台共用一份 Dockerfile（arm64 + amd64 由 base image 自适配）
docker build -t meeting-api:dev -f apps/meeting-api/Dockerfile apps/meeting-api/

# Apple Silicon 上想跑生产同款 amd64 镜像（包括 LibreOffice 原生路径）：
docker buildx build --platform linux/amd64 -t meeting-api:dev-amd64 \
  -f apps/meeting-api/Dockerfile apps/meeting-api/
```

> Mac M 系列上 LibreOffice 在 arm64 镜像里需要的字体目录与 amd64 不同：如果出现 `soffice: command not found` 或 PDF 输出乱码，先确认 Pod 用的是 amd64 镜像（通过 buildx + `--platform linux/amd64` 强制）。

#### C. Kubernetes（生产）

按 §五 完整流程即可。Java 容器没有 GPU 依赖，nodeSelector 默认走通用节点池；prod overlay 通过 ConfigMap patch 注入 `SPRING_PROFILES_ACTIVE=prod` 触发 `ProdProfileValidator` 严格检查（详情见 §5.7）。

### 5·5.2 Python 路径（ai-worker）

ai-worker 是平台差异最大的组件，按硬件能力走以下三条路径之一：

#### A. NVIDIA Linux + 真实模型（生产路径）

```bash
# 1. 主机检查
nvidia-smi                                          # 必须能输出 GPU 列表
nvidia-ctk runtime configure --runtime=docker      # 一次性配置
docker run --rm --gpus all nvidia/cuda:13.0.0-base-ubuntu22.04 nvidia-smi

# 2. 构建 CUDA 镜像（UV_EXTRAS=real-models 装齐 FlagEmbedding/funasr/pyannote.audio）
docker build -t ai-worker:cuda \
  -f apps/ai-worker/Dockerfile \
  --build-arg UV_EXTRAS=real-models \
  .

# 3. 灌权重到 PVC（K8s）—— 见 §5.8 + apps/ai-worker/scripts/stage_mock_weights.py
#    本地 docker compose 路径直接挂载 host 目录到容器 /opt/models。

# 4. 运行（K8s 走 base/ai-worker/statefulset.yaml；docker run 单机调试见下）
docker run --rm --gpus all \
  -e AI_WORKER_USE_FAKE_RUNTIME=false \
  -e AI_WORKER_USE_FAKE_ASR_RUNTIME=false \
  -e AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false \
  -e HF_HUB_OFFLINE=1 -e TRANSFORMERS_OFFLINE=1 \
  -e AI_WORKER_RABBITMQ_HOST=... -e AI_WORKER_MEETING_API_BASE_URL=... \
  -e AI_WORKER_CALLBACK_HMAC_SECRET=... -e AI_WORKER_INTERNAL_API_HMAC_SECRET=... \
  -v /opt/models:/opt/models:ro \
  -p 8090:8090 \
  ai-worker:cuda

# 5. 验证
curl -fsSL http://localhost:8090/internal/hardware | jq .   # torch/CUDA/模型 device
curl -fsSL http://localhost:8090/internal/ready    | jq .   # 模型 checksum guard
```

K8s 资源请求：1 × `nvidia.com/gpu`、8Gi RAM、2 vCPU 起步；多卡时通过 statefulset replica 横向扩展（每副本独占一卡），不要共享 GPU。

#### B. macOS / 任何无 GPU 机器（开发 + smoke 路径）

```bash
# 1. 装依赖（不需要 CUDA toolkit）
brew install python@3.11 uv             # 或 mise/asdf 走 .tool-versions
cd apps/ai-worker
uv sync --extra dev

# 2. 启动 fake runtime
export AI_WORKER_USE_FAKE_RUNTIME=true
export AI_WORKER_USE_FAKE_ASR_RUNTIME=true
export AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=true
export AI_WORKER_RABBITMQ_HOST=localhost
export AI_WORKER_MEETING_API_BASE_URL=http://localhost:8080
export AI_WORKER_CALLBACK_HMAC_SECRET=$(openssl rand -hex 32)
export AI_WORKER_INTERNAL_API_HMAC_SECRET=$(openssl rand -hex 32)
uv run ai-worker-api

# 3. 验证（fake runtime 下 /internal/ready 也会 200，不依赖权重）
curl -fsSL http://localhost:8090/internal/health
```

> **Apple Silicon (arm64) macOS 用户**：你可以装 `UV_EXTRAS=real-models`，详见下文 §5·5.2.C。**Intel macOS 不行**：`funasr` + `pyannote.audio` 的部分二进制依赖在 x86_64 macOS 上没有现成 wheel，`uv sync` 会编译失败。Linux + NVIDIA 走 §5·5.2.A，无 GPU 的 Linux 仍只能用 fake runtime。

#### C. macOS Apple Silicon 原生真实模型路径（全量模型 / 开发 + 单机演示）

> 用 `./deploy/ai-worker-apple-silicon.sh` 一站式跑通；下面是脚本展开的等价手工流程，便于排查。

```bash
# 0. 一站式入口（推荐）
./deploy/ai-worker-apple-silicon.sh stage     # 暂存 mock 权重（offline smoke）
HF_TOKEN=hf_xxx ./deploy/ai-worker-apple-silicon.sh weights   # 拉真实权重
./deploy/ai-worker-apple-silicon.sh run       # uv sync --extra real-models + 启动
./deploy/ai-worker-apple-silicon.sh verify    # /internal/hardware + /internal/ready

# 1. 装齐 real-models 依赖（FlagEmbedding + funasr + pyannote.audio）
cd apps/ai-worker
uv sync --extra dev --extra real-models       # 全部走 arm64 wheel / 源码安装

# 2. 权重落盘 —— 默认 ${HOME}/meeting-models
#    weights 会拉齐 BGE / Qwen3-ASR / Qwen3-ForcedAligner /
#    pyannote pipeline + submodels / CAM++ 声纹模型。
export AI_WORKER_MODELS_ROOT=${HOME}/meeting-models
# pyannote 需要在 HF 页面接受 license 并导出 HF_TOKEN
HF_TOKEN=hf_xxx ./deploy/ai-worker-apple-silicon.sh weights

# 3. Apple Silicon device 拆分 —— 别让 ASR / diarization 走 MPS
export AI_WORKER_USE_FAKE_RUNTIME=false
export AI_WORKER_USE_FAKE_ASR_RUNTIME=false
export AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false
export AI_WORKER_BGE_M3_DEVICE=mps
export AI_WORKER_BGE_RERANKER_DEVICE=mps
export AI_WORKER_BGE_M3_DTYPE=fp32       # MPS fp16 数值不稳
export AI_WORKER_BGE_RERANKER_DTYPE=fp32
export AI_WORKER_ASR_DEVICE=cpu          # funasr 算子未全 MPS 化
export AI_WORKER_DIARIZATION_DEVICE=cpu  # pyannote 同上
export AI_WORKER_BGE_M3_MODELS_DIR=${AI_WORKER_MODELS_ROOT}/bge-m3/v1
export AI_WORKER_BGE_RERANKER_MODELS_DIR=${AI_WORKER_MODELS_ROOT}/bge-reranker-v2-m3/v1
export AI_WORKER_QWEN3_ASR_MODELS_DIR=${AI_WORKER_MODELS_ROOT}/qwen3-asr-1.7b/v2026.05.1
export AI_WORKER_PYANNOTE_MODELS_DIR=${AI_WORKER_MODELS_ROOT}/pyannote/v3.1

# 4. 启动
uv run ai-worker-api

# 5. 验证 device 落点
curl -fsSL http://localhost:8090/internal/hardware | jq .
```

吞吐预期：embedding/rerank 接近单卡 MPS 上限，ASR ≈ 0.5× 实时，diarization ≈ 1× 实时；整体大约是单卡 RTX 4080 的 1/10。**这是开发 / 演示通道，不要拿到 prod**。生产仍走 §5·5.2.A 的 NVIDIA + CUDA 镜像。

#### D. macOS Apple Silicon + MPS（仅 BGE / rerank 数值核对）

```bash
# 仅用于开发期对照 fp32 数值，绝不进生产。
uv sync --extra real-bge                # FlagEmbedding 有 macOS wheel
export AI_WORKER_BGE_M3_DEVICE=mps
export AI_WORKER_BGE_RERANKER_DEVICE=mps
export AI_WORKER_BGE_M3_DTYPE=fp32      # MPS 上 fp16 数值不稳，禁用
export AI_WORKER_BGE_RERANKER_DTYPE=fp32
uv run ai-worker-api
```

`/internal/hardware` 会显示 `mps=true`，每个模型的 `device` 字段会落到 `mps`；任意一项报 `fp16` + `mps` 组合就停止使用。

### 5·5.3 meeting-web

唯一平台差异是 nginx 容器的 base 架构：
- `nginx:1.27-alpine` 已经提供 amd64 + arm64 multi-arch manifest，直接 `docker build` 即可在 Mac arm64 / Linux amd64 上分别生成 native 镜像。
- 想做跨架构发布：`docker buildx build --platform linux/amd64,linux/arm64 --push -t registry/meeting-web:v0.1.0 -f apps/meeting-web/Dockerfile .`

构建上下文必须是 repo root（Dockerfile 通过 `apps/meeting-web/...` 引用源码并 COPY 整个 `packages/meeting-contracts/`，否则 `npm run codegen` 在镜像里读不到合约）。

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
# 方式 1：重启 meeting-api 让 Flyway 自动 migrate（推荐）
docker restart meeting-api
# K8s 环境：
# kubectl -n meeting-dev rollout restart deployment/meeting-api

# 方式 2：用 Flyway CLI 直接对着 DB 跑（与 meeting-api 启动时一致）
docker run --rm -v "$(pwd)/apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration:/flyway/sql" \
  flyway/flyway:10 \
  -url=jdbc:postgresql://host.docker.internal:5432/meeting \
  -user=meeting -password=meeting_dev \
  -baselineOnMigrate=false \
  migrate

# 方式 3：纯 psql 顺序执行（应急 / debug 时用）
# 按文件名顺序拼接所有迁移；ON_ERROR_STOP=1 保证中途失败立刻退出。
ls apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V*.sql \
  | sort \
  | xargs -I{} psql -h localhost -U meeting -d meeting -v ON_ERROR_STOP=1 -f {}
# 当前已有的 8 个迁移（截至本文档发布时；以 git 仓库为准）：
#   V202605110001__initial_schema.sql
#   V202605110002__meeting_status_enum.sql
#   V202605140001__audio_upload_sessions.sql
#   V202605180001__export_jobs_render_options.sql
#   V202605180002__break_glass_requests.sql
#   V202605190001__meeting_documents.sql
#   V202605190002__meetings_glossary.sql
#   V202605190003__processing_tasks_hold_flag.sql
```

> 注意：DDL 命名以 `V{yyyyMMddHHmm}__desc.sql` 为准。文档历史版本曾引用过
> `V202501010000__initial_schema.sql` —— 那不是真实文件，已被当前 `V202605110001__...`
> 系列取代。psql 直跑路径不会写 `flyway_schema_history` 表，下一次 Flyway 启动会因
> `baseline-on-migrate=false`（prod 强制）拒绝继续；如果走的是方式 3，请同时手动
> `INSERT` 一条 baseline 行，或交给 Flyway 在 dev 上 `baseline-on-migrate=true` 自愈。

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
npm ci
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
# staging overlay 尚未落地，acceptance 阶段先落到 dev overlay
# （namespace meeting-dev）；overlays/staging/ 文件补齐后再切回 staging。
build → test → push-image → deploy-acceptance(meeting-dev) → e2e → deploy-prod
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
