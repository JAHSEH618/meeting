# 本地会议智能系统 · Meeting Intelligence

将会议音频转化为结构化知识资产：上传 → 转写 → 说话人分离 → 声纹识别 → 纪要生成 → 知识库 → RAG 问答 → 导出。

## 架构概览

系统由 **Java 业务层** 与 **Python 计算层** 组成，通过 RabbitMQ、TOS URI 和 internal callback API 协作。Java 是业务事实来源与权限中心；Python 只负责 AI Pipeline 执行，不直接写业务库。

```text
meeting-web (React SPA)
       │
       ▼
meeting-api (Spring Boot + COLA-V5 模块化单体)
  ├── api / bff       入口、鉴权、限流、响应聚合
  ├── user-auth       用户、租户、角色、权限
  ├── meeting         会议生命周期、转录、纪要、事项、导出
  ├── task            异步任务、状态机、步骤、重试、取消、幂等
  ├── storage         TOS 文件管理、分片上传、签名 URL
  ├── llm-gateway     模型路由、数据边界、Prompt、结构化输出、审计
  ├── speaker         声纹档案、授权、匹配确认、删除
  ├── rag             权限过滤、检索编排、citation、问答
  ├── document        文档上传、解析、知识入库
  ├── export          异步导出 Markdown / DOCX / PDF
  └── audit           处理、查看、导出、权限审计
       │
       ├── PostgreSQL + pgvector  （业务数据、任务状态、向量检索）
       ├── RabbitMQ               （异步任务队列）
       ├── 火山引擎 TOS            （音频、中间产物、导出文件）
       └── DashScope              （第三方 LLM，经 llm-gateway 审计）
       │
       ▼ (RabbitMQ 任务)
ai-worker (Python · FastAPI + Clean Architecture)
  ├── Celery / Dramatiq Worker  消费任务、执行可重试 step
  ├── Prefect / Temporal         编排 Pipeline DAG
  ├── LangGraph Agent           长会议总结、RAG、质量检查
  └── model-runtime             本地模型封装（ASR / Diarization / Speaker / Embedding）
       │
       └── 本地 GPU 模型
            ├── Qwen3-ASR                语音转写
            ├── pyannote / 3D-Speaker    说话人分离 & 声纹
            ├── bge-m3                   文本 Embedding
            └── bge-reranker-v2-m3       Rerank
```

完整架构图（Mermaid）见 [`docs/structure.md`](docs/structure.md)。

## 项目结构

```text
meeting/
├── apps/
│   ├── meeting-web/          React SPA 前端
│   ├── meeting-api/          Java 17 · Spring Boot · COLA-V5 模块化单体
│   └── ai-worker/            Python 3.11+ · FastAPI · AI 计算层
├── packages/
│   └── meeting-contracts/    OpenAPI / JSON Schema / 错误码契约
├── infra/
│   └── meeting-infra/        Docker Compose / K8s / Terraform / 部署脚本
├── docs/
│   ├── spec.md               一期可执行规格
│   ├── structure.md          架构图与要点
│   ├── app-api-contracts.md  应用间 API、消息、回调、JSON 契约
│   ├── 本地会议智能系统技术方案文档-优化版.md  完整技术方案
│   └── ddls/                 PostgreSQL DDL 源稿
└── README.md
```

## 技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 前端 | React | 会议列表、上传、转录编辑、纪要、RAG 问答、导出 |
| Java 后端 | Spring Boot + COLA-V5 | 模块化单体，按 client/adapter/app/domain/infrastructure/start 分层 |
| 数据库 | PostgreSQL + pgvector | 业务数据 + MVP 向量检索，启用 RLS 多租户隔离 |
| 消息队列 | RabbitMQ | Java → Python 异步任务投递 |
| 对象存储 | 火山引擎 TOS | 音频、中间 JSON、导出文件 |
| 第三方 LLM | DashScope (OpenAI-compatible) | 纪要、事项抽取、RAG 答案，经 llm-gateway 统一审计 |
| Python 计算 | FastAPI + Clean Architecture | 内部管理 API + AI Pipeline 执行 |
| 任务执行 | Celery / Dramatiq | Worker 消费队列、可重试 step |
| 流程编排 | Prefect / Temporal | Pipeline DAG、重试、取消、恢复 |
| Agent 编排 | LangGraph | 长会议总结、RAG 问答、质量检查 |
| ASR | Qwen3-ASR | 本地 GPU 转写 |
| 说话人分离 | pyannote / 3D-Speaker | 本地 GPU 执行 |
| 声纹识别 | CAM++ / ERes2NetV2 / WeSpeaker | 本地 GPU，embedding 信封加密存储 |
| Embedding | bge-m3 | 文本向量化 |
| Rerank | bge-reranker-v2-m3 | RAG 召回精排 |

## 一期范围

**必做：**
- 内置账号 + 租户隔离 + PostgreSQL RLS
- 会议创建、音频上传（断点续传，最长 4 小时）
- 本地 AI Pipeline：转码 → VAD → ASR → 说话人分离 → 声纹匹配 → 结构化转录
- 声纹注册、授权、候选匹配、人工确认
- DashScope 纪要生成、待办/决策/风险抽取
- 文档知识库（PDF/DOCX/TXT/Markdown）
- RAG 问答（pgvector，权限过滤，带 citation）
- Markdown / DOCX / PDF 异步导出
- 任务进度 SSE、失败重试、幂等 callback
- 删除任务、法定保全（legal hold）、break-glass 审计

**一期不做：** 实时字幕、在线协同编辑、飞书/企微/Jira 集成、OCR、全公司声纹搜索、CONFIDENTIAL/SECRET 会议自动 LLM、本地大模型

详见 [`docs/spec.md`](docs/spec.md)。

## 关键设计原则

1. **Java 管业务，Python 管计算。** Java 是主入口、业务事实来源和权限中心；Python 只执行 AI Pipeline，不直接写业务库，不自行判断权限。
2. **AI 产物 ≠ 业务事实。** AI 生成的待办、决策、风险默认是建议，用户确认后才成为业务事实。重生成只能产生 diff，不得覆盖用户已确认字段。
3. **安全等级控制 LLM 出网。** PUBLIC / INTERNAL 可调用 DashScope（发送前不做文本脱敏），CONFIDENTIAL / SECRET 一期 fail closed。
4. **声纹 embedding 信封加密。** 数据库不存明文 float 数组，使用 KMS 信封加密；撤销授权级联处理历史数据。
5. **RAG 权限由 Java 实时计算。** 向量库只做候选召回，不能作为权限事实来源。
6. **所有长任务可追溯。** 通过 `artifact_manifest` 记录输入、模型版本、Prompt 版本、配置和代码版本。
7. **MVP 模块化单体，按需拆分。** Java 后端一个 Spring Boot 应用，内部按 COLA-V5 分层 + 业务域隔离；仅在出现独立扩容、独立部署或合规隔离需求时拆服务。
8. **业务状态变更走 outbox。** 领域事件与业务事务同事务提交，避免 DB 提交后 MQ 丢事件。

## 快速开始

> 详细部署说明见 `infra/meeting-infra/` 和 [`docs/本地会议智能系统技术方案文档-优化版.md`](docs/本地会议智能系统技术方案文档-优化版.md)。

### 前置条件

- **Java 17+** · Maven/Gradle
- **Python 3.11+** · Poetry/uv
- **PostgreSQL 15+** + pgvector 扩展
- **RabbitMQ 3.x**
- **GPU** RTX 3090/4090 24GB（最低），专用于 Python ai-worker
- **火山引擎 TOS** Bucket（meeting-audio / meeting-artifacts / meeting-exports）
- **DashScope API Key**（第三方 LLM）

### 启动

```bash
# 1. 基础设施
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d

# 2. Java 后端
cd apps/meeting-api
./mvnw spring-boot:run

# 3. Python 计算层
cd apps/ai-worker
python -m ai_worker

# 4. 前端
cd apps/meeting-web
npm install && npm run dev
```

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/spec.md`](docs/spec.md) | 一期可执行规格：API 列表、数据库模型、RAG 规格、验收标准、错误码字典、默认配置 |
| [`apps/meeting-web/SPEC.md`](apps/meeting-web/SPEC.md) | 前端工程规格：页面、交互状态、API 对接、验收 |
| [`apps/meeting-api/SPEC.md`](apps/meeting-api/SPEC.md) | Java 后端工程规格：COLA 模块、业务域、流程、API、事务、安全 |
| [`apps/ai-worker/SPEC.md`](apps/ai-worker/SPEC.md) | Python AI Worker 工程规格：Pipeline、模型、callback、性能目标 |
| [`packages/meeting-contracts/SPEC.md`](packages/meeting-contracts/SPEC.md) | 跨工程契约规格：OpenAPI、JSON Schema、枚举、错误码、版本策略 |
| [`infra/meeting-infra/SPEC.md`](infra/meeting-infra/SPEC.md) | 基础设施工程规格：部署、队列、配置、观测、安全基线 |
| [`docs/structure.md`](docs/structure.md) | 架构 Mermaid 图 + 10 条架构要点 |
| [`docs/app-api-contracts.md`](docs/app-api-contracts.md) | 应用间契约：Public API、Internal Callback API、RabbitMQ 消息、TOS URI、幂等、版本、错误码 |
| [`docs/本地会议智能系统技术方案文档-优化版.md`](docs/本地会议智能系统技术方案文档-优化版.md) | 完整技术方案：术语表、建设目标、架构、选型、Pipeline、安全、部署 |
| [`docs/ddls/`](docs/ddls/) | PostgreSQL DDL 源稿 |
| [`packages/meeting-contracts/`](packages/meeting-contracts/) | 跨工程契约事实来源：OpenAPI / JSON Schema / 错误码 |

`apps/meeting-api/` 内部还按 COLA-V5 子项目拆分了 `SPEC.md`，见 [`apps/meeting-api/README.md`](apps/meeting-api/README.md)。

## License

[待定]
