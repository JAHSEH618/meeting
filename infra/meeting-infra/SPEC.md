# meeting-infra Spec

## 1. 工程定位

`meeting-infra` 是部署与本地环境工程，不包含业务代码。它负责提供本地开发、测试、预生产和生产部署所需的基础设施定义。

一期目标：

1. 支持本地启动 PostgreSQL + pgvector、RabbitMQ、必要的对象存储替代或 TOS 配置占位。
2. 提供 `meeting-api`、`meeting-web`、`ai-worker`、export runtime 的部署模板。
3. 定义 RabbitMQ 队列、交换机、DLQ 和资源隔离。
4. 定义配置、密钥、观测、日志、备份和安全基线。

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

## 4. 服务部署

| 服务 | 部署位置 | 资源重点 |
|---|---|---|
| `meeting-web` | 普通服务器或静态资源服务 | CPU / 网络 |
| `meeting-api` | 普通服务器 | CPU、数据库连接池、外部 API |
| `ai-worker` | 独立 GPU 机器 | GPU、显存、本地模型权重 |
| `export runtime` | 普通服务器 | CPU、LibreOffice、字体 |
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

预留：

1. `gpu-align-queue`，按需 Forced Alignment。
2. `rerank-queue`，后续独立 Rerank 扩容。

队列要求：

1. 每个队列配置 DLQ。
2. 消息包含 `taskId`、`tenantId`、`traceId`。
3. worker heartbeat 默认 15 到 30 秒。
4. lease TTL 默认 120 秒。
5. DLQ 保留默认 14 天。
6. 队列堆积、消费失败率、DLQ 数量必须有告警。

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

trace 要求：

1. 前端请求生成或传递 `X-Trace-Id`。
2. Java、RabbitMQ message、Python callback 使用同一 trace。
3. artifact manifest 记录 trace 或可关联 task。

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
6. export runtime 能生成 PDF。
7. 关键指标和日志可查询。
8. 密钥不进入 git。
9. legal hold 下生命周期清理不会删除受保护对象。
