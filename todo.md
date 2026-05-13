# Meeting Intelligence TODO

> 基于当前仓库状态与以下规格整理：`docs/spec.md`、各工程 `SPEC.md`、`docs/app-api-contracts.md`、`docs/spec-fixes.md`、`docs/spec-clarifications.md`、DDL migration、OpenAPI / JSON Schema 契约。
>
> 当前总体判断：仓库已有多工程骨架、核心契约、初始 DDL、base compose、Java 会议最小内存链路、Web 路由占位、ai-worker health / workflow registry；距离一期目标还需要先完成 MVP-0 纵向闭环，再展开音频、LLM、RAG、导出、声纹和合规能力。

## 阶段 0：开发准入与事实源收敛

### 工程：`packages/meeting-contracts`

- [x] 修正 `processing-task-message.schema.json` 的条件校验：`meetingId`、`documentId`、`speakerProfileId`、`speakerEnrollmentId`、`audioFileId`、`audioUri` 按 `taskType` 真实必填，避免 `TEXT_EMBEDDING` / `RAG_REINDEX` 传 `meetingId: null` 也通过校验。
- [x] 修正 `public-api.yaml` 中 `DELETE /meetings/{meetingId}` 当前错误引用 `CreateAudioUploadRequest` 的 requestBody，改为删除原因 / legal hold 检查所需 schema。
- [x] 补齐 OpenAPI response envelope 一致性：所有 4xx / 5xx 使用统一 `ApiResponse`，避免部分 internal API 直接返回裸 `ErrorInfo`。
- [x] 将 `scripts/check-consistency.sh` 中 lint / enum mismatch 从 `warn` 升级为 CI 失败，确保 contracts 真正成为硬门槛。
- [x] 固化 codegen 命令：TypeScript、Java、Python DTO / enum 生成或一致性 diff 校验必须可重复运行。
- [x] 增加 valid / invalid fixtures，覆盖 public API、internal callback、ai-worker internal API、processing-task-message、export-job-message。

### 工程：`apps/meeting-api`

- [x] 修正 `V202605110001__initial_schema.sql` 顶部 `#` 注释为合法 SQL 注释，保证 Flyway 可执行。
- [x] 对齐 DDL 与 contracts 的 `StepStatus`：当前 DDL `step_status` 含 `PARTIAL_SUCCEEDED`，但 contracts / spec 的 step status 不包含该值。
- [x] 增加 ArchUnit 测试 `meeting-api-start/src/test/java/com/meeting/api/ArchitectureBoundaryTest.java`，守住 COLA 依赖方向和 domain 禁止依赖 Spring Web / JDBC / MQ / SDK。
- [x] 增加 Maven 测试依赖与 Testcontainers 基线，先覆盖 PostgreSQL migration、RLS tenant context、RabbitMQ schema smoke。
- [x] 补齐 `application.yml` 基础配置：Flyway、datasource、RabbitMQ、TOS / MinIO、callback HMAC、chunk strategy、SSE、outbox、actuator prometheus。

### 工程：`infra/meeting-infra`

- [x] 增加仓库根或 compose 目录 `.env.example`，覆盖 PostgreSQL、RabbitMQ、MinIO、Vault、DashScope、callback HMAC、ai-worker HMAC、chunk strategy 等占位配置。
- [x] 验证 base compose 一键启动 PostgreSQL + pgvector、RabbitMQ、MinIO、Vault，并记录本地启动命令与健康检查。
- [x] 增加 RabbitMQ exchanges / bindings / policies，而不只是 queues，确保 outbox publisher 有稳定 routing 目标。
- [x] 补齐 Prometheus / Grafana dashboard 文件清单与 spec 对齐，至少覆盖 API、task pipeline、RAG、compliance、ai-worker GPU 的占位 dashboard。

### 工程：`apps/meeting-web`

- [x] 接入 contracts codegen 产物或建立手写类型一致性测试，替换当前 `src/shared/api/types.ts` 中长期手写 DTO 的风险点。
- [x] 移除 access token `sessionStorage` 持久化，改为内存 access token；refresh token 只走后端 HttpOnly cookie。
- [x] 增加前端基础测试栈落地：Vitest、React Testing Library、MSW、error mapper、idempotency key、SSE reducer。

### 工程：`apps/ai-worker`

- [x] 建立 RabbitMQ task message JSON Schema 校验入口，失败时按 spec fail-fast 并 callback `INVALID_TASK_MESSAGE`。
- [x] 实现 HMAC signing / nonce / timestamp 的 callback client 基础能力，而不仅保留 Protocol。
- [x] 增加 `POST /internal/rerank` 占位实现与契约测试，至少能按输入候选返回稳定 rank，后续再接入真实模型。

### 阶段 0 验收清单

> **当前会话状态（2026-05-13）：全部验收命令已通过。**
>
> 已知间歇性问题（非代码缺陷，环境依赖）：
> - `npm run codegen` apply 阶段可能因目标路径权限/锁失败（EPERM）；codegen-apply.sh 会报告并 exit 1。此时临时产物保留在 `.generated-apply/`，可手动复制。
> - `./mvnw verify` 需要 Docker daemon；无 Docker 时 `./mvnw test` 仍可通过（8 unit tests）。
> - `npm test` 偶尔出现 `--localstorage-file was provided without a valid path` 警告（Vite/Vitest 内部 issue），不影响测试结果。

| 验收命令 | 工作目录 | 测试数 | 环境前提 | 备注 |
|----------|----------|--------|----------|------|
| `npm run check` | `packages/meeting-contracts` | 8 steps | Node + Python3 | codegen 到 temp diff，不写目标路径 |
| `npm run codegen` | `packages/meeting-contracts` | 7 targets | Node + Python3 | temp 生成→cleanup→copy；需目标路径可写 |
| `./mvnw test` | `apps/meeting-api` | 8 unit | JDK 17 | 无需 Docker |
| `./mvnw verify` | `apps/meeting-api` | 20 total | JDK 17 + Docker | 含 PostgreSQL IT (7) + RabbitMQ IT (5) |
| `uv run pytest` | `apps/ai-worker` | ~39 | Python 3.11 | — |
| `uv run pyright ai_worker/` | `apps/ai-worker` | — | Python 3.11 | 0 errors |
| `npm test` | `apps/meeting-web` | 35 | Node 20 | localStorage warning 为 Vitest 已知噪音 |
| `npx tsc --noEmit` | `apps/meeting-web` | — | Node 20 | — |

**Docker 前提（仅 `./mvnw verify` 需要，Colima 用户）：**
```bash
colima start
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
```

## 阶段 1：MVP-0 纵向闭环

目标闭环：`meeting-web 登录/会议入口 -> meeting-api 创建会议和 processing task -> outbox / RabbitMQ -> ai-worker fake pipeline callback -> meeting-api 幂等接收 callback -> meeting-web 展示 task snapshot / SSE 或轮询`。

### 工程：`packages/meeting-contracts`

- [ ] 为 MVP-0 固定最小 DTO：auth、meeting、processing task、task step、SSE event、internal step callback、worker complete / fail。
- [ ] 为 MVP-0 提供 processing task valid / invalid fixture，覆盖禁止 `AUDIO_UPLOAD` / `SUMMARY` / `EXTRACTION` / `EXPORT` 进入 worker `pipelineSteps`。
- [ ] 提供 internal callback 回放 fixture，覆盖普通 step update、heartbeat update、complete phase=`WORKER_DAG`、fail。

### 工程：`apps/meeting-api-client`

- [ ] 补齐 `PageResult`、`ProcessingTaskDTO`、`ProcessingTaskStepDTO`、`TaskEventDTO`、`CreateProcessingTaskCommand`、`RetryTaskCommand`、`CancelTaskCommand`。
- [ ] 补齐 internal callback command 包：`StepCallbackCommand`、`StepProgressHeartbeatCommand`、`CompleteWorkerPhaseCommand`、`FailTaskCommand`。
- [ ] 将 Java enum 与 `schemas/common/enums.yaml` 建立一致性测试，包含 `ProcessingTaskPhase`、`ProcessingStepUpdateSource`、`RagAnswerCoverage`、`StaleStatus`。

### 工程：`apps/meeting-api-domain`

- [ ] 实现 `ProcessingTask` / `ProcessingTaskStep` 聚合和值对象，覆盖 status + phase 双状态机。
- [ ] 实现 task lease、attempt、heartbeat、cancel、retry、ORPHANED 领域规则。
- [ ] 定义 `ProcessingTaskRepository`、`CallbackEventRepository`、`MessagePublisher`、`StorageGateway` 基础端口。
- [ ] 定义 MVP-0 领域事件：`MeetingCreatedEvent`、`ProcessingTaskCreatedEvent`、`ProcessingTaskStepChangedEvent`、`WorkerPhaseCompletedEvent`。

### 工程：`apps/meeting-api-app`

- [ ] 将 `MeetingApplicationService` 从内存 demo 升级为真实应用用例：权限上下文、tenant context、idempotency、outbox 同事务。
- [ ] 实现创建 processing task 用例：`AUDIO_UPLOAD` Java-owned step 标记 `SUCCEEDED`，worker `pipelineSteps` 只包含 worker-owned step。
- [ ] 实现 outbox 写入与 `ProcessingTaskCreatedEvent`，消息 payload 符合 `processing-task-message.schema.json`。
- [ ] 实现 callback 应用服务：HMAC、timestamp、nonce、attempt、lease、tenant / meeting 关系、幂等 body hash。
- [ ] 实现 heartbeat 分支：`RUNNING && progress > 0` 不写 `callback_events`，latest-wins 更新 progress / heartbeat / lease。
- [ ] 实现 `/complete phase=WORKER_DAG` 只推进 `phase=WORKER_DAG_DONE` 并写 `WORKER_PHASE_COMPLETED`，不直接把 task 置为 `SUCCEEDED`。

### 工程：`apps/meeting-api-infrastructure`

- [ ] 替换 `InMemoryMeetingRepository` 为 PostgreSQL repository，至少覆盖 meetings、meeting_participants、processing_tasks、processing_task_steps、callback_events、domain_events_outbox。
- [ ] 实现事务开始设置 `app.tenant_id` / `app.user_id` / `app.request_id`，事务结束 reset tenant context。
- [ ] 实现 outbox publisher：`FOR UPDATE SKIP LOCKED`、批量 100、失败重试、单聚合 `sequence_no` 顺序。
- [ ] 实现 RabbitMQ publisher，投递 task message 到一期队列并携带 `taskId`、`tenantId`、`traceId`。

### 工程：`apps/meeting-api-adapter`

- [ ] 实现 `/api/auth/login`、`/api/auth/logout`、`/api/auth/me` 的内置账号 MVP。
- [ ] 实现 `/api/meetings` 创建 / 列表 / 详情，去掉 tenant header 伪上下文，改由登录态设置 tenant context。
- [ ] 实现 `/api/meetings/{meetingId}/processing-tasks`、`GET /api/processing-tasks/{taskId}`、retry、cancel。
- [ ] 实现 `/api/processing-tasks/{taskId}/events` SSE：建连先发 snapshot，支持 `Last-Event-Id`，不可续接时回退当前 snapshot。
- [ ] 将 `ProcessingTaskCallbackController` 从 accepted stub 改为读取完整 headers、原始 URI、body 并调用 app command。

### 工程：`apps/ai-worker`

- [ ] 实现 RabbitMQ consumer / WorkerRuntime MVP，消费 `MEETING_FULL_PIPELINE` task message。
- [ ] 实现 fake / smoke workflow：按 registry step 顺序回写 step RUNNING / SUCCEEDED、transcript smoke payload、complete phase=`WORKER_DAG`。
- [ ] 实现 callback retry：网络错误重试，409 停止重试并记录 `WRITEBACK_FAILED`。
- [ ] 实现 `/internal/workflows/{task_id}` 返回 fake workflow 状态，便于联调排查。

### 工程：`apps/meeting-web`

- [ ] 实现 LoginPage，接入 `/api/auth/login`，处理 AUTH_REQUIRED、账号锁定、密码错误和服务不可用。
- [ ] 实现 MeetingListPage 和 MeetingCreatePage，支持创建会议并跳转详情。
- [ ] 增加任务进度页面路由 `/meetings/:meetingId/tasks/:taskId`，展示 task status、phase、step、progress、errorCode、retryable。
- [ ] 实现 SSE client：支持 `Last-Event-Id`，重连失败后轮询 `GET /api/processing-tasks/{taskId}`。
- [ ] 修正路由命名与 spec：声纹档案使用 `/speaker-profiles`，导出入口使用 `/meetings/:meetingId/exports`。

## 阶段 2：音频上传与真实 Worker Pipeline

### 工程：`apps/meeting-api`

- [ ] 实现音频 multipart upload session：8 MiB 默认分片、10000 part 上限、24h TTL、part sha256 去重、complete 校验全文件 sha256。
- [ ] 实现 `meeting_files` 持久化和 TOS / MinIO 签名 URL 生成，原始音频落 `meeting-audio` 前缀。
- [ ] 实现音频 complete 后创建 `MEETING_FULL_PIPELINE` task，并同步会议状态 `CREATED -> PROCESSING`。
- [ ] 实现 task lease scanner：lease 过期置 `ORPHANED`，可重新入队，旧 attempt callback 不覆盖新 attempt。
- [ ] 实现 transcript callback 落库：`original_text`、`edited_text`、`current_text`、speaker label、timestamp precision、版本号。

### 工程：`apps/ai-worker`

- [ ] 接入 ArtifactStore / TOS 客户端，支持读取音频、写入质量报告、ASR 原始 JSON、diarization turns、artifact manifest。
- [ ] 实现 `AUDIO_PREPROCESS`：ffprobe、4 小时上限、采样率低于 16kHz reject、channel_map、质量告警。
- [ ] 实现 VAD 与 ASR 切片策略：默认 60s、范围 30-120s、overlap 默认 0.5s、记录 chunk strategy / pipeline version。
- [ ] 接入 ASR model runtime，并为 `ASR_RUNTIME_ERROR`、`ASR_GPU_OOM`、`ASR_MODEL_TIMEOUT` 返回稳定错误码。
- [ ] 接入 diarization model runtime，输出 `SPEAKER_00` 等匿名 label 和置信度。
- [ ] 实现 `TRANSCRIPT_MERGE`，输出结构化 segment 并 callback Java。

### 工程：`apps/meeting-web`

- [ ] 实现音频上传页面 `/meetings/:meetingId/audio`，支持分片上传、并发数 1-5、part 重试最多 3 次、取消 / 重试。
- [ ] 实现任务进度 step 展示，不只展示线性百分比；区分 worker step 与 Java-owned step。
- [ ] 实现转录查看页面，支持分页 / 虚拟滚动，默认使用 `currentText`。

## 阶段 3：Java LLM、纪要、事项与 STALE

### 工程：`apps/meeting-api`

- [ ] 实现 `TaskStepProgressService`，由 Java 推进 `SUMMARY` / `EXTRACTION`，source 固定 `JAVA_TASK_SERVICE`。
- [ ] 实现 `WORKER_PHASE_COMPLETED` listener：`MEETING_FULL_PIPELINE` 进入 `JAVA_LLM_RUNNING`，非 LLM worker task 直接进入 `TERMINAL`。
- [ ] 实现 DashScope `LlmGateway`：OpenAI-compatible、security level fail closed、prompt template、input/output hash、token、latency、schema 校验和审计。
- [ ] 实现会议纪要生成，保存 `meeting_minutes`、evidence segment、`evidence_text_snapshot`、artifact manifest。
- [ ] 实现待办、决策、风险抽取；AI 建议与用户确认后的业务事实分离。
- [ ] 实现转录编辑：版本冲突校验，保留 `original_text`，更新 `edited_text` / `current_text`，将纪要、事项、RAG chunk、导出标记 STALE。
- [ ] 实现 `CONFIDENTIAL` / `SECRET` 自动 LLM 返回 `SECURITY_LEVEL_BLOCKED`。

### 工程：`apps/meeting-web`

- [ ] 实现转录编辑、版本冲突提示和下游 STALE 提示。
- [ ] 实现纪要页：章节、evidence、重生成、LLM 阻断提示。
- [ ] 实现待办 / 决策 / 风险页面，支持接受、拒绝、编辑、evidence 展示。
- [ ] 实现 `SECURITY_LEVEL_BLOCKED` 固定业务提示：`一期不支持该安全等级的自动 LLM 处理`。

## 阶段 4：声纹注册、匹配与人工确认

### 工程：`apps/meeting-api`

- [ ] 实现 speaker profile、enrollment、授权、撤销、删除和 audit 领域模型。
- [ ] 实现 `SPEAKER_ENROLLMENT` task 创建与 callback 校验，允许 `meetingId=null` 但必须校验 profile / enrollment 归属。
- [ ] 实现 speaker embedding KMS 信封加密：AES-256-GCM、12 bytes nonce、16 bytes tag、wrapped DEK、checksum、key version。
- [ ] 实现 speaker candidates callback 落库：明文 embedding 只在 internal callback 内短暂存在，成功后不写日志、不进入 public DTO。
- [ ] 实现 speaker confirm / reject，更新转录 speaker 显示、RAG chunk freshness 和审计。
- [ ] 实现撤销授权级联：新匹配排除 profile，历史 person_id 软屏蔽，相关 RAG chunk 标记 STALE 并异步去标识重建。

### 工程：`apps/ai-worker`

- [ ] 接入 speaker embedding model runtime，支持参考音频和会议 speaker label embedding。
- [ ] 实现仅在 Java 授权的 knownParticipants / profile 范围内候选匹配，不做全公司搜索。
- [ ] speaker-candidates callback 始终携带 `embedding.values`，禁止改为 TOS 明文 artifact。
- [ ] callback 成功或重试耗尽后清理进程内 embedding 明文引用。

### 工程：`apps/meeting-web`

- [ ] 实现 speaker 确认页面：匿名 label、候选人、置信度、确认、拒绝、候选过期提示。
- [ ] 实现声纹档案页面：档案、授权、参考音频 enrollment、撤销、删除。
- [ ] 确保声纹 embedding、模型原始输出和内部 artifact 不出现在页面、日志、监控 breadcrumb。

## 阶段 5：文档知识库与 RAG

### 工程：`apps/meeting-api`

- [ ] 实现文档上传、TOS / MinIO 文件元信息、解析状态、删除、reindex。
- [ ] 使用 JVM 文档解析库解析 PDF / DOCX / TXT / Markdown；扫描 PDF 和图片 OCR 返回明确不支持错误。
- [ ] 实现 chunk 策略：source type、source version、chunk strategy version、content hash、status、stale_status。
- [ ] 实现 pgvector + keyword retrieval + metadata filter + PostgreSQL 权限二次校验。
- [ ] 实现 `RerankGateway` 同步调用 ai-worker `/internal/rerank`：HMAC、3s timeout、503 / 5xx 降级、400 / 401 不降级并告警。
- [ ] 实现 RAG 答案生成：scope 计算、citation、coverage=`TRANSCRIPT_ONLY|FULL`、query log、LLM log、artifact manifest。
- [ ] coverage 从 `TRANSCRIPT_ONLY` 到 `FULL` 时使旧 answer cache 失效。

### 工程：`apps/ai-worker`

- [ ] 接入 bge-m3 embedding runtime，支持会议、纪要、事项和文档 chunk embedding callback。
- [ ] 接入 bge-reranker-v2-m3 lazy-load，并实现 `/internal/rerank` 真实 rerank。
- [ ] 模型加载失败返回稳定错误码，Java 决定是否按规则降级。

### 工程：`apps/meeting-web`

- [ ] 实现文档知识库页面：上传、解析状态、扫描 PDF 不支持、reindex、删除。
- [ ] 实现 RAG 页面：scope 选择、提问、coverage 标签、citation、无可检索内容、429 限流。
- [ ] citation 点击定位到会议 segment / 文档 chunk；权限撤销或音频归档时展示退化状态。

## 阶段 6：异步导出

### 工程：`packages/meeting-contracts`

- [ ] 补齐 export DTO、export job status、短链撤销、STALE 确认相关 schema 和 fixtures。

### 工程：`apps/meeting-api`

- [ ] 实现 `export_jobs` 应用用例：创建、列表、详情、取消、短链撤销。
- [ ] 导出任务绑定 `minutesVersion`、`transcriptVersion`、`ragVersion`，内容 STALE 时要求确认或先重生成。
- [ ] outbox 投递 `export-job-message.schema.json` 到 `export-queue`。
- [ ] 在 Java 进程内实现 `export-queue` consumer，只做消息适配并调用 app command。
- [ ] 实现 `ExportGateway`：Markdown、DOCX、PDF，PDF 通过 LibreOffice headless 或等价 runtime。
- [ ] 导出文件写入 `meeting-exports` 前缀，下载只返回后端签名 URL，短链可撤销。

### 工程：`apps/meeting-web`

- [ ] 实现会议导出页面 `/meetings/:meetingId/exports`，支持 Markdown / DOCX / PDF 异步创建、状态、取消、下载、短链撤销。
- [ ] 导出入口展示 STALE 提示和版本绑定摘要。

### 工程：`infra/meeting-infra`

- [ ] 为 meeting-api 镜像或运行环境补齐 LibreOffice headless 和字体包，并增加 PDF 转换 smoke test。

## 阶段 7：合规、删除、legal hold 与 break-glass

### 工程：`apps/meeting-api`

- [ ] 实现 legal hold 创建、释放、命中阻断和 audit event。
- [ ] 实现 deletion job：计划生成、执行锁、legal hold 二次检查、对象删除 / 生命周期标记、失败项摘要。
- [ ] 实现 deletion certificate：对象 hash、范围、执行人、时间、失败项和审计摘要。
- [ ] 删除任务只有全部目标处理成功时才推进 meeting `DELETED`；失败或 legal hold 命中保持原状态。
- [ ] 实现 break-glass：reason、审批人、时间窗口、审批 / 拒绝、审计。
- [ ] 实现 audit 查询与导出，覆盖处理、查看、导出、权限、声纹访问、break-glass。

### 工程：`apps/meeting-web`

- [ ] 实现 legal hold 管理页面。
- [ ] 实现 deletion jobs 和 deletion certificate 页面。
- [ ] 实现 break-glass 申请、审批、拒绝和审计页面。
- [ ] 合规页面所有写操作按后端权限和稳定错误码控制，不以前端隐藏作为安全边界。

### 工程：`infra/meeting-infra`

- [ ] 增加 legal hold 下生命周期清理不会删除受保护对象的部署 / 运维 smoke test。
- [ ] 补齐备份恢复 runbook：PostgreSQL RPO 5min / RTO 30min、对象 hash 校验、RabbitMQ 依赖 outbox 重放。

## 阶段 8：观测、安全、性能与发布

### 工程：`apps/meeting-api`

- [ ] 为 public endpoint、callback endpoint、outbox publisher、SSE emitter 增加 Micrometer timer / counter。
- [ ] 实现健康检查：PostgreSQL、RLS tenant smoke、RabbitMQ、TOS / MinIO、outbox、KMS、必要队列、ai-worker rerank。
- [ ] prod profile fail-fast：缺少 HMAC、chunk strategy、ai-worker base URL / HMAC / rerank model、RLS 关闭、CONFIDENTIAL / SECRET 误允许 LLM。
- [ ] 增加性能测试与告警指标：meeting list p95、callback p95、outbox lag、SSE 首字节、RAG p95。

### 工程：`apps/ai-worker`

- [ ] 实现 `GET /internal/models` 返回模型版本、checksum、device、状态、最近错误。
- [ ] 增加 GPU 指标、RTF、step 失败率、OOM 退出策略。
- [ ] 生产启动禁止联网下载模型权重；模型 checksum 不匹配拒绝 ready。
- [ ] 补齐模型准入清单 `docs/model-registry.md` 的 checksum、内网制品路径和审批记录。

### 工程：`apps/meeting-web`

- [ ] 增加 CSP / sanitizer / Markdown XSS 测试，RAG answer、纪要、evidence 文本不能直接渲染不可信 HTML。
- [ ] 接入前端监控，仅采集 route、error code、requestId、traceId 和浏览器环境，不采集正文、文件名原文或 token。
- [ ] 实现 route-level code split、转录虚拟滚动、长 Markdown 懒加载，控制首屏 JS gzip 预算。
- [ ] 增加 Playwright E2E：登录 -> 创建会议 -> 上传 -> 任务进度 -> 转录 -> 纪要 -> RAG -> 导出，以及 `SECURITY_LEVEL_BLOCKED` 分支。

### 工程：`infra/meeting-infra`

- [ ] 增加 full-stack compose 或 K8s dev overlay：meeting-api、meeting-web、ai-worker 镜像构建和健康检查。
- [ ] 增加 Dockerfile：meeting-api、meeting-web、ai-worker；meeting-api 镜像超过 1.5GB 时重新评估 export runtime 拆分。
- [ ] 增加 K8s base / dev overlay：deployment、service、configmap、servicemonitor、GPU node selector、PDB / HPA。
- [ ] 增加 Prometheus rules：outbox backlog、RabbitMQ DLQ、callback auth fail、RAG rerank 降级、KMS 失败、GPU OOM、export 失败。
- [ ] 确保真实密钥不进入 git，部署只使用 `.env`、K8s Secret 或密钥管理系统注入。

## 持续性工程任务

- [ ] 每个阶段完成后运行 contracts check、Java compile/test、Python pytest、Web type-check/test，并把命令写入对应工程 README。
- [ ] 每个新增业务域先创建符合 SPEC 的 package，再落 Controller / ApplicationService / Aggregate / RepositoryImpl / Gateway。
- [ ] 每次修改字段、枚举、错误码、状态机或 API，先改事实源：Flyway migration、OpenAPI、JSON Schema、common enums / error-codes。
- [ ] 保持 Java 管业务事实和权限、Python 只做 AI Pipeline、Web 只消费 Public API / SSE 的边界。
- [ ] 保持 AI 产物与业务事实分离：重生成只产生 diff / 新建议，不覆盖用户已确认字段。
- [ ] 保持 PUBLIC / INTERNAL 可自动 LLM，CONFIDENTIAL / SECRET 自动 LLM fail closed。
