# meeting-api Java 部署运行手册

本文档是 `meeting-api` Java 服务的详细部署手册，覆盖本地验证、单台
CentOS 服务器 Docker/Compose 部署、K8s 开发/验收部署，以及生产 K8s 部署
前后的门禁、回滚和排障。脚本入口以 `deploy/meeting-api-java.sh` 为准，
共享的基础设施、镜像、K8s overlay 和环境变量说明见 `deploy/DEPLOY.md`。

`apps/meeting-api` 是 Spring Boot 3.3 / Java 17 的模块化单体，分为六个
Maven 模块：

| 模块 | 职责 |
|------|------|
| `meeting-api-start` | 启动入口、Spring 配置、profile 校验、健康检查 |
| `meeting-api-adapter` | REST、SSE、内部 callback controller 和协议适配 |
| `meeting-api-app` | 用例编排、事务、任务调度、租户上下文 |
| `meeting-api-domain` | 聚合、实体、领域服务、Repository/Gateway 端口 |
| `meeting-api-infrastructure` | PostgreSQL、RabbitMQ、对象存储、KMS、DashScope、LibreOffice 网关 |
| `meeting-api-client` | DTO、命令、结果对象、生成的 API client |

## 0. 部署决策

| 目标 | 是否可以直接开始 | 标准命令 | 说明 |
|------|------------------|----------|------|
| 本地 Java 冒烟 | 可以 | `./deploy/meeting-api-java.sh compose` | 启动依赖、`meeting-api` 和 fake `ai-worker`。 |
| 本地验收 | 可以，建议带观测组件 | `./deploy/meeting-api-java.sh compose --with-observability` | Prometheus/Grafana 规则检查需要这个模式。 |
| 单台 CentOS 服务器 | 可以，直接接阿里云 OSS | 手工启动 PostgreSQL/RabbitMQ，然后用 `STORAGE_TYPE=oss` 启动 Java | 不再部署 MinIO。 |
| K8s dev / acceptance | 可以，先安装工具 | 准备 PostgreSQL/RabbitMQ + OSS Secret 后手工 `kustomize build` / `kubectl apply` | kind/minikube 需要先 build 并 load 镜像。 |
| 生产 K8s | 不能直接复用 dev 默认值 | ExternalSecrets/Vault + 托管依赖，然后 `./deploy/meeting-api-java.sh k8s prod` | 禁止 dev 密码、localhost、in-memory auth 和 fake worker。 |

Java API 的对象存储统一直接对接阿里云 OSS：`STORAGE_TYPE=oss`，
`OSS_ENDPOINT` / `OSS_REGION` / `OSS_ACCESS_KEY_ID` /
`OSS_ACCESS_KEY_SECRET` 必须显式配置。不要再按 MinIO 部署 Java API。
生产部署不执行 `k8s-deps prod`；默认推荐托管 PostgreSQL/RabbitMQ、
阿里云 OSS、prod overlay 和
ExternalSecrets/Vault/SealedSecrets 注入配置。

## 1. 从 0 开始的 CentOS 准备

CentOS 上建议先走 Docker/Compose 路径把 Java 服务和依赖整体跑通，再决定
是否迁移到 K8s。下面命令适用于 CentOS Stream 9 / RHEL 9 类系统，CentOS 7
需要根据系统仓库替换安装命令。

### 1.1 基础包

```bash
sudo dnf update -y
sudo dnf install -y git curl jq unzip tar ca-certificates openssl
sudo dnf install -y java-17-openjdk java-17-openjdk-devel
java -version
```

要求 Java 主版本必须是 17。Maven Enforcer 只接受 `[17,18)`，Java 21 或
Java 25 会被拒绝。

### 1.2 Docker 和 Compose

```bash
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
docker version
docker compose version
```

执行 `usermod` 后需要重新登录当前用户，或者临时用 `sudo docker ...` 验证。

### 1.3 拉取代码

```bash
git clone https://github.com/JAHSEH618/meeting.git
cd meeting
git status --short
```

部署前工作区应干净，避免把未确认的本地改动带到服务器。

### 1.4 端口和资源

单机 Compose 默认会用到：

| 端口 | 服务 |
|------|------|
| `8080` | `meeting-api` |
| `8090` | `ai-worker` |
| `5432` | PostgreSQL |
| `5672` / `15672` | RabbitMQ / 管理端 |
| 出站 `443` | 阿里云 OSS endpoint |
| `9090` | Prometheus，只有 observability profile 才需要 |
| `3000` | Grafana 或 Web 侧服务，取决于 profile |

CentOS 防火墙按实际暴露范围放行，不建议把 DB/MQ 管理端直接暴露到公网。
OSS 走 HTTPS 出站访问阿里云 endpoint，不需要在本机部署或暴露 MinIO。

```bash
sudo firewall-cmd --state
sudo firewall-cmd --add-port=8080/tcp --permanent
sudo firewall-cmd --reload
```

## 2. 预检

每次部署前先执行：

```bash
git status --short
java -version
docker version
docker compose version
```

K8s 路径还需要：

```bash
kubectl version --client
kustomize version
helm version
```

工具要求：

| 工具 | 要求 | 原因 |
|------|------|------|
| JDK | 17，严格 `[17,18)` | Maven Enforcer 会拒绝其他主版本。 |
| Maven | 使用 `apps/meeting-api/mvnw` | 固定 Maven 版本，避免服务器全局 Maven 差异。 |
| Docker Engine | 24+ | 支持 Testcontainers、镜像构建、Compose。 |
| Node 20 | 仅 contract/codegen 路径需要 | Java 生成 client 依赖合同产物。 |
| `kubectl` / `kustomize` / `helm` | 仅 K8s 路径需要 | 渲染 overlay、安装命名空间依赖、等待 rollout。 |

macOS 本地调试时脚本会自动查找 Java 17：

```bash
/usr/libexec/java_home -v 17
```

如果本地用 Colima 跑 Testcontainers：

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## 3. 命令矩阵

| 流程 | 命令 | 使用场景 |
|------|------|----------|
| 完整 Java 验证 | `./deploy/meeting-api-java.sh test` | 部署前后端门禁，等价 CI 后端验证。 |
| 构建 jar | `./deploy/meeting-api-java.sh jar` | 快速打包检查。 |
| 构建并运行 jar | `./deploy/meeting-api-java.sh jar --run` | 主机原生调试，依赖需另行启动。 |
| 构建 Docker 镜像 | `./deploy/meeting-api-java.sh image [tag]` | 本地 Compose、kind 或推送镜像仓库。 |
| Apple Silicon 构建 amd64 | `./deploy/meeting-api-java.sh image meeting-api:v0.1.0 --cross` | 目标生产节点是 linux/amd64 时做本地 sanity check。 |
| 单机 OSS 部署 | 见 §7 手工 Compose 命令 | CentOS 或本地跑通 Java + PostgreSQL + RabbitMQ + 阿里云 OSS。 |
| 带观测的验收 | `./deploy/meeting-api-java.sh compose --with-observability` | 启动 Prometheus/Grafana。 |
| K8s 应用部署 | 见 §8 手工 `kustomize build` / `kubectl apply` | OSS 直连路径需要先准备 PostgreSQL/RabbitMQ、OSS Secret 和 overlay 配置。 |
| 数据库迁移参考 | `./deploy/meeting-api-java.sh migrate` | 打印 Flyway / restart / psql 方案。 |

## 4. 测试门禁

部署前必须跑：

```bash
./deploy/meeting-api-java.sh test
```

该命令会执行：

| 层级 | 覆盖内容 |
|------|----------|
| 单元测试 | JUnit 5 / Mockito，覆盖 domain 和 app 行为 |
| 架构测试 | COLA 模块边界检查 |
| 集成测试 | Testcontainers PostgreSQL、RabbitMQ、对象存储网关相关用例 |
| Spring 上下文 | Boot 配置、健康检查、profile 校验 |

常见失败和处理：

| 现象 | 处理 |
|------|------|
| Maven Enforcer 提示 JDK 错误 | 修正 `JAVA_HOME` 到 Java 17。 |
| Testcontainers 找不到 Docker socket | 确认 Docker 服务运行，必要时设置 Colima/OrbStack 环境变量。 |
| Ryuk bind-mount 失败 | 设置 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`。 |
| 端口被占用 | 停止本地 Compose stack 或占用端口的容器。 |

不要把 `compile` 通过当成部署门禁。真正的 Java 部署门禁是
`./mvnw verify -q`，脚本已经封装在 `test` 子命令里。

## 5. Jar 部署路径

构建 jar：

```bash
./deploy/meeting-api-java.sh jar
```

构建并运行：

```bash
./deploy/meeting-api-java.sh jar --run
```

产物位置：

```text
apps/meeting-api/meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar
```

jar 模式不会自动启动 PostgreSQL/RabbitMQ。对象存储直接走阿里云 OSS，不在
服务器上启动 MinIO。开发机可以只用 Compose 起 DB/MQ：

```bash
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d postgres rabbitmq
```

jar 模式最小环境变量：

```bash
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=meeting
export POSTGRES_USER=meeting
export POSTGRES_PASSWORD=meeting_dev

export RABBITMQ_HOST=localhost
export RABBITMQ_PORT=5672
export RABBITMQ_USER=meeting
export RABBITMQ_PASS=meeting_dev

export STORAGE_TYPE=oss
export STORAGE_BUCKET_AUDIO=meeting-audio-auska
export STORAGE_BUCKET_ARTIFACTS=meeting-artifacts
export STORAGE_BUCKET_EXPORTS=meeting-exports
export OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
export OSS_REGION=cn-hangzhou
export OSS_ACCESS_KEY_ID=<aliyun-ram-access-key-id>
export OSS_ACCESS_KEY_SECRET=<aliyun-ram-access-key-secret>

export AI_WORKER_BASE_URL=http://localhost:8090
export AI_WORKER_CALLBACK_HMAC_SECRET=change-me-callback-secret-32bytes
export AI_WORKER_INTERNAL_API_HMAC_SECRET=change-me-internal-secret-32bytes
export KMS_MASTER_KEY_ID=dev-kms-master-key
export MEETING_KMS_MASTER_KEY_BASE64="$(openssl rand -base64 32)"
```

验证：

```bash
curl -fsSL http://localhost:8080/actuator/health/readiness | jq .
curl -fsSL http://localhost:8080/actuator/health | jq .
```

`/actuator/health/readiness` 只检查 Java 应用是否可接流量；聚合
`/actuator/health` 还包括 `aiWorker`、`rabbitMqQueue`、`postgresRls`、
`kms` 和 `outboxBacklog` 等组件。OSS 连通性用上传/下载业务冒烟验证，不再
用 MinIO 健康端点代表对象存储。

## 6. Docker 镜像路径

构建默认镜像：

```bash
./deploy/meeting-api-java.sh image
```

构建指定 tag：

```bash
./deploy/meeting-api-java.sh image meeting-api:v0.1.0
```

手工等价命令：

```bash
docker build -t meeting-api:dev \
  -f apps/meeting-api/Dockerfile \
  apps/meeting-api/
```

Apple Silicon 机器为 linux/amd64 做本地构建检查：

```bash
docker buildx create --use 2>/dev/null || true
./deploy/meeting-api-java.sh image meeting-api:v0.1.0 --cross
```

生产发布建议由 CI 使用 `docker buildx build --platform linux/amd64 --push`
直接推送到镜像仓库，并在 release 或 prod overlay 中使用 digest 固定版本。

镜像内容：

| 组件 | 用途 |
|------|------|
| `eclipse-temurin:17-jre-jammy` | Java 运行时 |
| LibreOffice | DOCX/PDF 导出转换 |
| Spring Boot jar | `meeting-api-start` |
| `/tmp` 和 `/tmp/soffice` 可写目录 | K8s 运行和 LibreOffice 转换需要 |

## 7. 单台 CentOS + 阿里云 OSS 部署

单台 CentOS 从 0 跑通时，Java API 只需要本机 PostgreSQL/RabbitMQ 和远端
阿里云 OSS。不要为 Java API 部署 MinIO。

先启动 DB/MQ：

```bash
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d postgres rabbitmq
```

构建镜像：

```bash
./deploy/meeting-api-java.sh image meeting-api:dev
```

启动 `meeting-api`，并显式切到 OSS：

```bash
STORAGE_TYPE=oss \
STORAGE_BUCKET_AUDIO=meeting-audio-auska \
STORAGE_BUCKET_ARTIFACTS=meeting-artifacts \
STORAGE_BUCKET_EXPORTS=meeting-exports \
OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com \
OSS_REGION=cn-hangzhou \
OSS_ACCESS_KEY_ID=<aliyun-ram-access-key-id> \
OSS_ACCESS_KEY_SECRET=<aliyun-ram-access-key-secret> \
AI_WORKER_BASE_URL=http://ai-worker:8090 \
  docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
  --profile full-stack up -d meeting-api
```

如果 ai-worker 也在同机 fake/runtime 容器运行：

```bash
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
  --profile workstation up -d ai-worker
```

最小服务组成：

| 服务 | 来源 | 健康门禁 |
|------|------|----------|
| PostgreSQL + pgvector | compose base | container healthcheck |
| RabbitMQ | compose base | management healthcheck |
| 阿里云 OSS | 云服务 | 通过上传/下载业务冒烟验证 |
| meeting-api | `full-stack` profile | `/actuator/health/readiness` |
| ai-worker fake/runtime | `workstation` profile 或远端服务 | `/internal/health` |

为什么启动门禁看 readiness 而不是聚合 health：`AiWorkerHealthIndicator`
属于聚合健康检查，`ai-worker` 未就绪时聚合 health 可能为 DOWN，但 Java 服务
本身已经可启动。

验证：

```bash
./deploy/deploy.sh health
curl -fsSL http://localhost:8080/actuator/health | jq .
```

`./deploy/meeting-api-java.sh compose` 和 `./deploy/deploy.sh local` 仍然是本地
全栈便利命令，会启动 compose 文件里的历史 MinIO 服务。阿里云 OSS 部署不要
使用它作为标准路径；按本节显式设置 `STORAGE_TYPE=oss` 和 `OSS_*`。

单机 Compose 不等于生产高可用。生产需要把 DB、MQ、阿里云 OSS、Secret、
监控和 ai-worker GPU runtime 按生产要求拆出来。

## 8. K8s dev / acceptance

K8s dev/acceptance 也按阿里云 OSS 直连处理。对象存储不在集群里安装 MinIO，
而是在 overlay 中设置 `STORAGE_TYPE=oss`、`OSS_ENDPOINT`、`OSS_REGION`，
并通过 Secret 注入 `OSS_ACCESS_KEY_ID` 和 `OSS_ACCESS_KEY_SECRET`。

kind/minikube 示例：

```bash
./deploy/deploy.sh build
kind create cluster --name meeting-dev

# PostgreSQL/RabbitMQ 可以使用托管服务，也可以按团队内部 Helm values 单独安装。
# 不要为了 Java API 对象存储去安装 MinIO。

kind load docker-image meeting-api:dev meeting-web:dev ai-worker:dev --name meeting-dev
```

创建 Java API Secret 时必须包含 OSS 凭据：

```bash
kubectl create namespace meeting-dev --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic meeting-api-secret \
  -n meeting-dev \
  --from-literal=POSTGRES_USER=meeting \
  --from-literal=POSTGRES_PASSWORD=<postgres-password> \
  --from-literal=RABBITMQ_USER=meeting \
  --from-literal=RABBITMQ_PASS=<rabbitmq-password> \
  --from-literal=OSS_ACCESS_KEY_ID=<aliyun-ram-access-key-id> \
  --from-literal=OSS_ACCESS_KEY_SECRET=<aliyun-ram-access-key-secret> \
  --from-literal=AI_WORKER_CALLBACK_HMAC_SECRET=<32-byte-secret> \
  --from-literal=AI_WORKER_INTERNAL_API_HMAC_SECRET=<32-byte-secret> \
  --from-literal=DASHSCOPE_API_KEY=<dashscope-key> \
  --from-literal=KMS_MASTER_KEY_ID=<kms-key-id> \
  --from-literal=MEETING_KMS_MASTER_KEY_BASE64=<base64-32-bytes> \
  --dry-run=client -o yaml | kubectl apply -f -
```

overlay / ConfigMap 需要包含：

```yaml
STORAGE_TYPE: "oss"
STORAGE_BUCKET_AUDIO: "meeting-audio-auska"
STORAGE_BUCKET_ARTIFACTS: "meeting-artifacts"
STORAGE_BUCKET_EXPORTS: "meeting-exports"
OSS_ENDPOINT: "https://oss-cn-hangzhou.aliyuncs.com"
OSS_REGION: "cn-hangzhou"
```

如果 K8s 集群和 OSS bucket 在同地域同 VPC，优先使用内网 endpoint，例如：

```yaml
OSS_ENDPOINT: "https://oss-cn-hangzhou-internal.aliyuncs.com"
```

渲染并应用 dev overlay：

```bash
kustomize build infra/meeting-infra/k8s/overlays/dev --enable-helm \
  > deploy/.kustomize-dev.yaml
rg 'STORAGE_TYPE|OSS_ENDPOINT|OSS_REGION|POSTGRES_HOST|RABBITMQ_HOST' \
  deploy/.kustomize-dev.yaml
kubectl apply -f deploy/.kustomize-dev.yaml
```

然后手工等待应用就绪：

```bash
kubectl rollout status deployment/meeting-api -n meeting-dev --timeout=300s
kubectl rollout status deployment/meeting-web -n meeting-dev --timeout=300s
kubectl rollout status statefulset/ai-worker -n meeting-dev --timeout=600s
```

注意：`./deploy/deploy.sh k8s-deps dev` 是旧的本地依赖捷径，会安装
PostgreSQL/RabbitMQ/MinIO。按当前 OSS 直连要求，不把它作为 Java API 标准
部署步骤。

验证：

```bash
kubectl get pods -n meeting-dev -o wide
kubectl rollout status deployment/meeting-api -n meeting-dev --timeout=300s
kubectl port-forward -n meeting-dev svc/meeting-api 8080:8080
curl -fsSL http://localhost:8080/actuator/health | jq .
```

## 9. 生产 K8s 部署

生产部署必须按固定顺序执行：

1. 通过 release gate。
2. 发布目标节点架构对应的不可变镜像。
3. 准备基础设施、Secret 和 Config。
4. 确认数据库备份和 Flyway 迁移策略。
5. 应用 prod overlay。
6. 验证 rollout、健康检查、日志和业务冒烟。

不要把 dev/acceptance 只换 namespace 后当成生产部署。prod overlay 会设置
`SPRING_PROFILES_ACTIVE=prod`，并强制
`SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`。`ProdProfileValidator` 会在启动
阶段 fail-fast，避免带着 dev 默认值对外服务。

### 9.1 Go / No-Go 门禁

| 门禁 | 可以继续 | 必须停止 |
|------|----------|----------|
| Java 验证 | `./deploy/meeting-api-java.sh test` 退出码为 0 | 单测、架构测试、集成测试或 Spring context 任一失败 |
| Release 镜像 | `meeting-api`、`meeting-web`、CUDA `ai-worker` 镜像已按 digest 推送 | 只有本地 tag、架构错误或缺少 `ai-worker:cuda-*` |
| 基础设施 | PostgreSQL、RabbitMQ、阿里云 OSS、KMS、监控已准备 | 误用 dev 密码、单节点生产 DB/MQ、OSS bucket 未建好或没有日志/指标入口 |
| Secret | `meeting-api-secret` 和 `ai-worker-secret` 已同步到 `meeting-prod` | Secret 缺失、demo HMAC、OSS AK/SK 缺失、callback/internal HMAC 不一致 |
| 数据库 | 已备份、恢复路径明确、Flyway 是迁移 owner | 没有备份、手工 SQL 没有 Flyway history、baseline-on-migrate 打开 |
| AI worker | Linux + NVIDIA + CUDA worker 已就绪并加载真实模型 | Apple Silicon、fake runtime、checksum 缺失或 CPU-only worker |

### 9.2 Release 制品

构建和测试 Java 服务：

```bash
./deploy/meeting-api-java.sh test
./deploy/meeting-api-java.sh image meeting-api:<release>
```

生产 linux/amd64 镜像发布示例：

```bash
docker buildx build --platform linux/amd64 \
  -t registry.example.com/meeting-api:<release> \
  -f apps/meeting-api/Dockerfile \
  --push apps/meeting-api
```

生产必须使用一组匹配的镜像：

| 镜像 | 要求 |
|------|------|
| `meeting-api` | Java 17 runtime 镜像，架构匹配目标节点 |
| `meeting-web` | 同一个 release commit 构建出的 Web 镜像 |
| `ai-worker` | CUDA 镜像，例如 `ai-worker:cuda-<release>` |

不要把 Apple Silicon arm64-only 镜像推给 linux/amd64 集群。不要在生产使用
lean CPU/fake `ai-worker` 镜像，因为生产 readiness 依赖真实 BGE/ASR/
diarization 依赖和模型权重。

### 9.3 生产基础设施

推荐生产依赖形态：

| 依赖 | 生产建议 |
|------|----------|
| PostgreSQL | 托管 RDS / Cloud SQL / 自管 HA PostgreSQL，并启用 pgvector |
| RabbitMQ | 托管 MQ 或 HA RabbitMQ，definitions 由运维应用 |
| 对象存储 | 阿里云 OSS，直接使用公网或内网 endpoint |
| Secret | Vault / ExternalSecrets / SealedSecrets |
| KMS | 优先云 KMS；本地 KMS 必须使用稳定的 32 字节 base64 master key |
| 监控 | 托管 Prometheus/Grafana 或集群监控栈 |

不要在生产集群内安装 MinIO，也不要执行 `./deploy/deploy.sh k8s-deps prod`
作为 Java API 标准生产步骤；该旧命令会安装 MinIO。生产对象存储统一使用
阿里云 OSS，bucket、生命周期、加密、跨域、RAM 权限由云资源侧提前准备。

### 9.4 Secret 和 Config

`meeting-api-secret` 必须在 rollout 前存在：

| Key | 要求 |
|-----|------|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | 真实数据库凭据 |
| `RABBITMQ_USER` / `RABBITMQ_PASS` | 真实 MQ 凭据 |
| `AI_WORKER_CALLBACK_HMAC_SECRET` | 非 demo，至少 32 字节 |
| `AI_WORKER_INTERNAL_API_HMAC_SECRET` | 非 demo，且不同于 callback secret |
| `DASHSCOPE_API_KEY` | 真实 provider key |
| `KMS_MASTER_KEY_ID` | 不能是 `dev-kms-master-key` |
| `MEETING_KMS_MASTER_KEY_BASE64` | 使用本地 KMS gateway 时必填 |
| `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` | 有权访问目标 OSS bucket 的 RAM 凭据 |

`ai-worker-secret` 必须和 `meeting-api` 使用同一组 HMAC：

| Key | 要求 |
|-----|------|
| `AI_WORKER_CALLBACK_HMAC_SECRET` | 与 `meeting-api-secret` 相同 |
| `AI_WORKER_INTERNAL_API_HMAC_SECRET` | 与 `meeting-api-secret` 相同 |
| `AI_WORKER_ADMIN_JWT_SECRET` | 非 demo 管理密钥 |

OSS RAM 策略至少要覆盖目标 bucket 的对象读写生命周期：

| 能力 | 用途 |
|------|------|
| `oss:PutObject` | 客户端签名上传、服务端写入导出物 |
| `oss:GetObject` / `oss:HeadObject` | 完成上传后确认对象存在和大小、下载预签名 |
| `oss:DeleteObject` | 删除会议、导出物或清理任务 |

不要使用主账号 AK/SK。生产建议使用专用 RAM 用户或 STS 角色，并限制到
`STORAGE_BUCKET_AUDIO`、`STORAGE_BUCKET_ARTIFACTS`、`STORAGE_BUCKET_EXPORTS`
对应 bucket。

生产配置必须通过 prod overlay、ExternalSecret 或平台配置注入：

| 配置 | 生产值 |
|------|--------|
| `AI_WORKER_BASE_URL` | 集群 DNS 或内部 URL，不能是 localhost |
| `MEETING_TENANTS_ACTIVE` | 非空租户列表 |
| `STORAGE_TYPE` | 固定为 `oss` |
| `OSS_ENDPOINT` | 阿里云 OSS endpoint，ECS/K8s 同地域优先内网 endpoint |
| `OSS_REGION` | OSS bucket 所在地域，例如 `cn-hangzhou` |
| `STORAGE_BUCKET_AUDIO` / `STORAGE_BUCKET_ARTIFACTS` / `STORAGE_BUCKET_EXPORTS` | 已存在的 OSS bucket 名 |
| Auth mode | 不能是 `in-memory` |
| `SPRING_FLYWAY_BASELINE_ON_MIGRATE` | `false` |

当前 prod overlay 已把 `STORAGE_TYPE` 切到 `oss`，并默认使用
`https://oss-cn-hangzhou-internal.aliyuncs.com` / `cn-hangzhou`。如果 bucket
不在杭州地域，或集群不能走阿里云内网 endpoint，先修改
`infra/meeting-infra/k8s/overlays/prod/kustomization.yaml` 后再部署。

应用清单前检查 Secret：

```bash
kubectl get secret meeting-api-secret -n meeting-prod
kubectl get secret ai-worker-secret -n meeting-prod
```

### 9.5 数据库迁移

推荐路径是让 `meeting-api` 启动时运行 Flyway。生产 rollout 前：

1. 创建数据库备份。
2. 明确恢复责任人、RTO 和 RPO。
3. Review 新增 `V*.sql`。
4. 保持 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`。
5. 不要用裸 `psql` 执行生产迁移，除非是 break-glass 操作，并且之后修复
   `flyway_schema_history`。

如果 Flyway 在启动后失败，停止 rollout。选择前向修复 migration 或从已验证
备份恢复，不要关闭 `ProdProfileValidator`，也不要重新打开 baseline-on-migrate
强行启动。

### 9.6 Rollout 顺序

先渲染并检查 prod 清单：

```bash
kustomize build infra/meeting-infra/k8s/overlays/prod --enable-helm \
  > deploy/.kustomize-prod.yaml
rg 'SPRING_PROFILES_ACTIVE|SPRING_FLYWAY_BASELINE_ON_MIGRATE|AI_WORKER_BASE_URL|MEETING_TENANTS_ACTIVE|STORAGE_TYPE|OSS_ENDPOINT|OSS_REGION' \
  deploy/.kustomize-prod.yaml
kubectl diff -f deploy/.kustomize-prod.yaml
```

通过标准脚本部署：

```bash
./deploy/meeting-api-java.sh k8s prod
kubectl rollout status deployment/meeting-api -n meeting-prod --timeout=300s
kubectl rollout status deployment/meeting-web -n meeting-prod --timeout=300s
kubectl rollout status statefulset/ai-worker -n meeting-prod --timeout=600s
```

如果 rollout 由其他 release controller 观察，只有在已有人员盯着状态和告警时，
才使用：

```bash
./deploy/deploy.sh k8s-prod --no-wait
```

### 9.7 上线后验证

rollout 成功后执行：

```bash
kubectl logs -n meeting-prod deployment/meeting-api --tail=300 \
  | grep -E 'ProdProfileValidator|Flyway|Started'
kubectl port-forward -n meeting-prod svc/meeting-api 8080:8080
curl -fsSL http://localhost:8080/actuator/health/readiness | jq .
curl -fsSL http://localhost:8080/actuator/health | jq .
```

验收标准：

| 检查 | 期望 |
|------|------|
| Readiness | rollout gate 为 `UP` |
| 聚合健康 | ai-worker 和依赖在线后为 `UP` |
| 日志 | 无 `ProdProfileValidator`、Flyway、HMAC、KMS、DB、MQ、storage 错误 |
| RabbitMQ | definitions 已加载，队列没有无界积压 |
| Outbox | `outboxBacklog` 不是 DOWN |
| AI worker | `/internal/ready` 返回 200，`/internal/hardware` 显示 CUDA |

## 10. 数据库迁移参考

首选：让 `meeting-api` 启动时自动运行 Flyway。

```bash
kubectl rollout restart deployment/meeting-api -n meeting-dev
kubectl rollout status deployment/meeting-api -n meeting-dev --timeout=300s
```

Flyway CLI 路径：

```bash
docker run --rm \
  -v "$(pwd)/apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration:/flyway/sql" \
  flyway/flyway:10 \
  -url=jdbc:postgresql://host.docker.internal:5432/meeting \
  -user=meeting \
  -password=meeting_dev \
  -baselineOnMigrate=false \
  migrate
```

SQL debug 路径：

```bash
ls apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V*.sql \
  | sort \
  | xargs -I{} psql -h localhost -U meeting -d meeting -v ON_ERROR_STOP=1 -f {}
```

生产不要直接用裸 `psql` 迁移，除非同步处理 `flyway_schema_history`。裸 SQL
不会把版本标记为已应用。

## 11. 回滚

| 失败点 | 回滚动作 |
|--------|----------|
| 镜像启动失败 | 把 overlay 镜像 tag/digest 指回上一个已知可用版本后重新 apply |
| Flyway 迁移应用前失败 | 修 SQL，并在一次性数据库上重跑 |
| Flyway 部分应用失败 | 停止 rollout，恢复 DB 备份或提交前向修复 migration |
| `ProdProfileValidator` 失败 | 修 Secret/ConfigMap，不要禁用 prod profile |
| readiness 失败 | 看 `/actuator/health/readiness`，先查 DB/MQ/对象存储 |

常用命令：

```bash
kubectl describe pod -n meeting-prod -l app.kubernetes.io/name=meeting-api
kubectl logs -n meeting-prod deployment/meeting-api --tail=300
kubectl rollout undo deployment/meeting-api -n meeting-prod
```

## 12. 排障

| 现象 | 常见原因 | 处理 |
|------|----------|------|
| `Detected JDK version` | `JAVA_HOME` 指向非 17 | `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` |
| Testcontainers socket 错误 | Docker socket 发现失败 | 确认 Docker 运行，设置对应 socket 环境变量 |
| `CreateContainerConfigError` | Secret 缺失 | apply 前创建或同步 `meeting-api-secret` / `ai-worker-secret` |
| `ProdProfileValidator failed` | prod config 仍有 dev 默认值 | 按 §9.4 对照检查 |
| `/actuator/health` DOWN 但 readiness UP | `aiWorker` 或依赖聚合项未就绪 | rollout 看 readiness，验收看 aggregate |
| `outboxBacklog` DOWN | 事件发布积压 | 查 `domain_events_outbox` 和应用日志 |
| OSS 初始化失败 | `STORAGE_TYPE=oss` 但 `OSS_ENDPOINT` / `OSS_REGION` / AK/SK 缺失 | 补齐 OSS 配置和 Secret，确认 bucket 所在 region |
| `oss put/head/delete failed` | RAM 权限、bucket、endpoint 或网络不正确 | 检查 RAM policy、bucket 名、内网/公网 endpoint 和出站 HTTPS |
| LibreOffice 导出失败 | 临时目录不可写或架构错误 | 查 `/tmp/soffice`、镜像架构、`LIBREOFFICE_BINARY=soffice` |
| Flyway `relation already exists` | SQL 曾被手工执行 | 用 disposable DB 复现，repair 或提交前向 migration |

## 13. 最终检查表

部署窗口开始前确认：

- `./deploy/meeting-api-java.sh test` 退出码为 0。
- release image tag 或 digest 已按目标节点架构构建。
- DB 备份存在，恢复演练路径明确。
- 目标 namespace 中存在 `meeting-api-secret`。
- prod profile 相关值都是非 demo 值。
- 对象存储 endpoint 可以从 pod 内访问。
- RabbitMQ definitions 已加载。
- `kubectl rollout status deployment/meeting-api` 已在 staging/acceptance 成功。
- ai-worker 在线后，聚合 `/actuator/health` 为 `UP`。

相关文档：

- `deploy/DEPLOY.md`
- `docs/runbooks/phase-j-acceptance.md`
- `apps/meeting-api/SPEC.md`
- `deploy/meeting-api-java.sh`
