# meeting-infra Spec

## 1. 工程定位

`meeting-infra` 是部署与本地环境工程，不包含业务代码。它负责提供本地开发、测试、预生产和生产部署所需的基础设施定义。

一期目标：

1. 支持本地启动 PostgreSQL + pgvector、RabbitMQ、必要的对象存储替代或 TOS 配置占位。
2. 提供 `meeting-api`、`meeting-web`、`ai-worker`、export runtime 的部署模板。
3. 定义 RabbitMQ 队列、交换机、DLQ 和资源隔离。
4. 定义配置、密钥、观测、日志、备份和安全基线。

## 1.1 开发准入

本工程分为两个开发层级，避免把本地依赖环境和全栈镜像发布混在一个准入条件里：

1. `base compose`：本地开发必选，只启动 PostgreSQL、RabbitMQ、MinIO / TOS 替身、Vault / KMS 替身和可选观测组件。Java、Web、Python 应用可以在宿主机直接运行并连接这些依赖。
2. `full-stack compose`：联调和镜像 smoke test 使用，包含 `meeting-api`、`meeting-web`、`ai-worker` 镜像构建。只有对应 Dockerfile 和镜像裁剪策略落地后才作为 CI 必过项。

进入多人并行开发前，`base compose` 必须可一键启动；进入端到端验收前，`full-stack compose` 或等价 K8s dev overlay 必须可用。

## 2. 目录规划

```text
docker/
  compose/
k8s/
  base/
  overlays/
    dev/
    staging/
    prod/
terraform/
scripts/
```

MVP 可先使用 Docker Compose；生产部署优先 K8s + Terraform。

## 3. 基础组件

| 组件 | 一期要求 |
|---|---|
| PostgreSQL 15+ | 启用 pgvector，保存业务数据、任务状态、审计和向量 |
| RabbitMQ 3.x | 异步任务、资源隔离队列、DLQ |
| TOS | 原始音频、中间 JSON、导出文件 |
| DashScope | 第三方 LLM provider，由 `meeting-api` 调用 |
| LibreOffice headless | PDF 转换或 DOCX/PDF 导出 runtime |
| Prometheus / Grafana | 指标采集和 dashboard |
| 日志系统 | Java、Python、RabbitMQ、PostgreSQL 日志集中查询 |

## 3.1 Docker Compose 服务清单

`docker/compose/docker-compose.yml` 是本地 `base compose`，至少包含：

| service | image / build | ports | healthcheck | volumes / env |
|---|---|---|---|---|
| `postgres` | `pgvector/pgvector:pg15` 或内部等价镜像 | `5432:5432` | `pg_isready` | `postgres-data`；初始化 `docs/ddls/001_initial_schema.sql` |
| `rabbitmq` | `rabbitmq:3.13-management` | `5672:5672`、`15672:15672` | `rabbitmq-diagnostics ping` | `rabbitmq-data`；创建一期队列和 DLQ |
| `minio` | `minio/minio` | `9000:9000`、`9001:9001` | `/minio/health/live` | `minio-data`；本地替代 TOS |
| `vault-dev` | `hashicorp/vault` | `8200:8200` | `/v1/sys/health` | 仅 local / dev 替代 KMS |
| `prometheus` | `prom/prometheus` | `9090:9090` | `/-/healthy` | scrape configs |
| `grafana` | `grafana/grafana` | `3000:3000` | `/api/health` | dashboard provisioning |
| `loki` / `tempo` | 官方镜像或内部镜像 | internal | HTTP health | 日志 / trace 本地调试 |

`prometheus`、`grafana`、`loki` / `tempo` 可以使用 compose profile 控制，但引用的配置文件必须随仓库提交。Compose 不写真实密钥；仓库应提交 `.env.example`，同时保留安全默认值，使单人本地开发在没有 `.env` 时也能启动非敏感依赖。

`full-stack compose` 或等价 dev overlay 在进入端到端验收前必须补齐：

| service | image / build | ports | healthcheck | volumes / env |
|---|---|---|---|---|
| `meeting-api` | build `apps/meeting-api`，镜像内安装 LibreOffice headless 和字体包 | `8080:8080` | `/actuator/health` | env 注入 DB / MQ / TOS / DashScope / KMS；本地 PDF 转换 smoke |
| `meeting-web` | build `apps/meeting-web` nginx | `5173:80` | HTTP 200 | API base URL |
| `ai-worker` | build `apps/ai-worker` | `8090:8090` | `/internal/health` | 模型权重只读挂载；GPU runtime 可选 |

`meeting-api` 镜像内嵌 LibreOffice headless 和字体包时必须使用多阶段构建并裁剪不需要的 LibreOffice 模块；如果压缩后镜像体积超过 1.5GB，下一轮部署设计应重新评估独立 export 服务或 sidecar，避免 K8s pull 和滚动更新时间不可控。

## 4. 服务部署

| 服务 | 部署位置 | 资源重点 |
|---|---|---|
| `meeting-web` | 普通服务器或静态资源服务 | CPU / 网络 |
| `meeting-api` | 普通服务器 | CPU、数据库连接池、外部 API |
| `ai-worker` | 独立 GPU 机器 | GPU、显存、本地模型权重 |
| `export runtime` | `meeting-api` Java 进程内，一期不单独部署 | CPU、LibreOffice、字体 |
| PostgreSQL | 数据库节点 | 存储、备份、RLS 验证 |
| RabbitMQ | 消息队列节点 | 队列堆积、DLQ、连接数 |

`ai-worker` 与 `meeting-api` 可分开部署，通过 RabbitMQ、TOS URI 和 internal callback API 协作。

## 5. RabbitMQ 队列

一期必须配置：

| 队列 | 资源类型 | 用途 |
|---|---|---|
| `audio-cpu-queue` | CPU / IO | ffmpeg、VAD、质量检测 |
| `gpu-asr-queue` | GPU | ASR |
| `gpu-diar-queue` | GPU | Diarization |
| `gpu-speaker-queue` | GPU | speaker embedding / matching |
| `embed-queue` | GPU / CPU | text embedding |
| `llm-queue` | API / GPU | 纪要、抽取、RAG 问答 |
| `export-queue` | CPU / IO | Markdown / DOCX / PDF 导出 |

一期不创建的预留队列：

1. `gpu-align-queue`：Forced Alignment 一期按需在 worker 进程内执行或后续再拆队列。
2. `rerank-queue`：Rerank 一期启用但在 `ai-worker` 进程内 lazy-load 执行，不在 Compose / RabbitMQ definitions 中创建独立队列。

队列要求：

1. 每个队列配置 DLQ。
2. 消息包含 `taskId`、`tenantId`、`traceId`。
3. worker heartbeat 默认 15 到 30 秒。
4. lease TTL 默认 120 秒。
5. DLQ 保留默认 14 天。
6. 队列堆积、消费失败率、DLQ 数量必须有告警。
7. 一期 `export-queue` 由 `meeting-api` Java 进程内消费；独立 export worker 仅后续预留。

## 6. 配置与密钥

必须配置：

1. PostgreSQL URL、用户名、密码。
2. RabbitMQ URL、用户名、密码。
3. TOS endpoint、bucket、access key、secret key。
4. DashScope API key。
5. callback HMAC secret。
6. KMS master key 或 KMS 访问配置。
7. JWT / session secret。
8. CORS allowed origins。
9. 模型权重路径和 checksum。
10. `meeting.chunk.strategy-version`，例如 `chunk-2026.05.1`。
11. callback HMAC timestamp skew，例如 `meeting.callback.timestamp-skew-seconds=300`。
12. callback 幂等事件保留期，例如 `meeting.callback-events.retention-days=30`。

密钥不得写入仓库。Compose 可使用 `.env.example` 占位，真实值通过本机 `.env`、K8s Secret 或密钥管理系统注入。

## 7. 存储桶规划

建议 bucket 或前缀：

```text
meeting-audio/
  tenant/{tenantId}/meeting/{meetingId}/raw/
  tenant/{tenantId}/meeting/{meetingId}/normalized/
meeting-artifacts/
  tenant/{tenantId}/task/{taskId}/
meeting-exports/
  tenant/{tenantId}/meeting/{meetingId}/export/{exportId}/
```

要求：

1. 原始音频、标准化音频、中间 JSON、导出文件分前缀隔离。
2. 文件元信息、hash、版本和 security level 由 `meeting-api` 落库。
3. 生命周期清理必须先检查 legal hold。
4. 导出短链必须可撤销。

## 8. 可观测性

必须采集：

1. API 请求量、延迟、错误率。
2. callback 鉴权失败、幂等冲突、迟到 attempt。
3. RabbitMQ 队列堆积、消费速率、DLQ。
4. PostgreSQL 连接池、慢查询、RLS fail closed 计数。
5. LLM 调用延迟、token、限流、schema 校验失败。
6. ai-worker GPU 利用率、显存、RTF、step 失败率。
7. export 成功率、转换耗时、文件大小。
8. deletion job、legal hold、break-glass 审计事件。
9. KMS 可用性、密钥轮换事件和声纹 embedding 加密失败率。
10. outbox publisher backlog、单聚合发布顺序冲突和重试次数。

trace 要求：

1. 前端请求生成或传递 `X-Trace-Id`。
2. Java、RabbitMQ message、Python callback 使用同一 trace。
3. artifact manifest 记录 trace 或可关联 task。

Dashboard 文件清单：

| 文件 | 内容 |
|---|---|
| `observability/dashboards/meeting-api-overview.json` | QPS、p50/p95/p99、5xx 率、Controller 错误码 TopN、Hikari 连接池 |
| `observability/dashboards/task-pipeline.json` | step RTF、失败率、ORPHANED 数、DLQ 深度、callback 冲突 |
| `observability/dashboards/rag-quality.json` | 检索数量、rerank 命中、citation 缺失率、LLM token 和延迟 |
| `observability/dashboards/compliance.json` | legal hold 数、deletion job 状态、break-glass 次数、审计事件 |
| `observability/dashboards/ai-worker-gpu.json` | GPU 利用率、显存、模型加载状态、OOM、actor backlog |

## 8.1 K8s 目录与备份恢复

K8s 清单结构：

```text
k8s/base/
  meeting-api/{deployment,service,configmap,hpa,pdb,servicemonitor}.yaml
  meeting-web/{deployment,service,configmap}.yaml
  ai-worker/{statefulset,service,configmap,nodeselector-gpu}.yaml
  postgres/{statefulset,service,backup-cronjob}.yaml
  rabbitmq/{statefulset,service,policy}.yaml
k8s/overlays/{dev,staging,prod}/kustomization.yaml
```

`dev` overlay 是端到端联调前的必备项；`staging` 和 `prod` overlay 是预生产 / 生产发布准入，不阻塞本地 MVP-0 纵向切片开发。新增 overlay 时必须与 Terraform、备份和密钥策略一起评审。

备份 / 恢复：

1. PostgreSQL 使用 `pg_basebackup + WAL` 归档到对象存储；RPO `5min`，RTO `30min`；每季度恢复演练一次。
2. TOS / MinIO 对象记录 `sha256`，删除证书保留对象 hash、bucket、key、删除时间和操作者。
3. RabbitMQ 使用 quorum queue 三节点；跨地域灾备不依赖队列持久化，依赖 PostgreSQL outbox 重放。
4. Grafana dashboard、Prometheus rules、RabbitMQ definitions 和 K8s manifests 必须纳入 git。

## 9. 安全基线

1. `meeting-api` Public API 只对前端入口开放。
2. `ai-worker` FastAPI 内部接口只允许内网访问。
3. callback API 使用内网控制 + HMAC。
4. PostgreSQL 普通业务账号不允许绕过 RLS。
5. 模型权重和镜像来自内网制品库，生产不得临时联网下载。
6. DashScope API key 只注入 `meeting-api`，不注入 `meeting-web`。
7. 声纹 embedding 加密依赖 KMS，密钥轮换需要 runbook。
8. break-glass 必须有 reason、审批人、时间窗口和 audit event。

## 10. 验收标准

1. 本地环境可启动 PostgreSQL + pgvector 和 RabbitMQ。
2. RabbitMQ 队列和 DLQ 按一期要求创建。
3. `meeting-api` 能连接数据库、RabbitMQ 和 TOS 配置。
4. `ai-worker` 能访问 RabbitMQ、TOS、模型权重路径和 callback URL。
5. `meeting-web` 能访问 `meeting-api`。
6. `meeting-api` 进程内 export consumer 能消费 `export-queue` 并生成 PDF。
7. 关键指标和日志可查询。
8. 密钥不进入 git。
9. legal hold 下生命周期清理不会删除受保护对象。
