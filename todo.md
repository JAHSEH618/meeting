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

> **当前会话状态（2026-05-13）：阶段 0 准入脚手架验收命令已通过。**
>
> 阶段 0 当前定位：本地开发准入、契约/codegen 门禁、基础 Testcontainers smoke、Web/AI Worker 测试栈已可重复验证；真实持久化链路、Java callback 安全闭环、outbox/RabbitMQ 实投递、worker fake pipeline 消费与回调仍属于阶段 1 MVP-0 范围，不能视为集成风险已解除。
>
> 已知间歇性问题（非代码缺陷，环境依赖）：
> - `apps/meeting-api` 的所有 `./mvnw` 命令必须使用 JDK 17（enforcer 范围 `[17,18)`）；默认 JDK 21 会失败。macOS 可先执行 `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`。
> - `./mvnw verify` 需要 Docker daemon；本轮已在 Colima 下通过。无 Docker 或未导出 Colima socket 时会先通过 Testcontainers preflight 快速失败并提示前提，`./mvnw test` 仍可通过。
> - `npm run codegen` 是有副作用的维护命令，会原地更新 TS / Python / Java generated 文件；验收优先使用无副作用的 `npm run codegen:check-temp` 或 `npm run check`。Python codegen 依赖 `datamodel-codegen`，缺失时本地 drift 检查会失败。

| 验收命令 | 工作目录 | 测试数 | 环境前提 | 备注 |
|----------|----------|--------|----------|------|
| `npm run check` | `packages/meeting-contracts` | 8 steps | Node + Python3 + JDK 17 | codegen 到 temp diff，不写目标路径；不依赖全局 spectral / npx 临时安装 |
| `npm run codegen:check-temp` | `packages/meeting-contracts` | 7 targets | Node + Python3 + JDK 17 | 纯检查；生成到 temp 后 diff，不写目标路径 |
| `npm run codegen` | `packages/meeting-contracts` | 7 targets | Node + Python3 + JDK 17 | 有副作用维护命令；temp 生成→cleanup→copy，需目标路径可写 |
| `./mvnw test` | `apps/meeting-api` | unit + ArchUnit | **JDK 17 only** | 无需 Docker；覆盖架构边界、会议 service/repository/controller 行为基线 |
| `./mvnw verify` | `apps/meeting-api` | unit + IT | **JDK 17 only** + Docker | 使用 Colima socket + Testcontainers baseline，含 preflight、PostgreSQL IT、RabbitMQ topology definitions 结构化校验 |
| `uv run pytest` | `apps/ai-worker` | ~39 | Python 3.11 | — |
| `uv run pyright ai_worker/` | `apps/ai-worker` | — | Python 3.11 | 0 errors |
| `npm test` | `apps/meeting-web` | 35 | Node 20 | 测试脚本禁用 Node experimental WebStorage，避免 localStorage warning |
| `npx tsc --noEmit` | `apps/meeting-web` | — | Node 20 | — |

**Docker 前提（仅 `./mvnw verify` 需要，Colima 用户）：**
```bash
colima start
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_RYUK_DISABLED=true
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw verify -q
```

## 阶段 1：MVP-0 纵向闭环

目标闭环：`meeting-web 登录/会议入口 -> meeting-api 创建会议和 processing task -> outbox / RabbitMQ -> ai-worker fake pipeline callback -> meeting-api 幂等接收 callback -> meeting-web 展示 task snapshot / SSE 或轮询`。

### 工程：`packages/meeting-contracts`

- [x] 为 MVP-0 固定最小 DTO：auth、meeting、processing task、task step、SSE event、internal step callback、worker complete / fail。
- [x] 为 MVP-0 提供 processing task valid / invalid fixture，覆盖禁止 `AUDIO_UPLOAD` / `SUMMARY` / `EXTRACTION` / `EXPORT` 进入 worker `pipelineSteps`。
- [x] 提供 internal callback 回放 fixture，覆盖普通 step update、heartbeat update、complete phase=`WORKER_DAG`、fail。

### 工程：`apps/meeting-api-client`

- [x] 补齐 `PageResult`、`ProcessingTaskDTO`、`ProcessingTaskStepDTO`、`TaskEventDTO`、`CreateProcessingTaskCommand`、`RetryTaskCommand`、`CancelTaskCommand`。
- [x] 补齐 internal callback command 包：`StepCallbackCommand`、`StepProgressHeartbeatCommand`、`CompleteWorkerPhaseCommand`、`FailTaskCommand`。
- [x] 将 Java enum 与 `schemas/common/enums.yaml` 建立一致性测试，包含 `ProcessingTaskPhase`、`ProcessingStepUpdateSource`、`RagAnswerCoverage`、`StaleStatus`。

### 工程：`apps/meeting-api-domain`

- [x] 实现 `ProcessingTask` / `ProcessingTaskStep` 聚合和值对象，覆盖 status + phase 双状态机。
- [x] 实现 task lease、attempt、heartbeat、cancel、retry、ORPHANED 领域规则。
- [x] 定义 `ProcessingTaskRepository`、`CallbackEventRepository`、`MessagePublisher`、`StorageGateway` 基础端口。
- [x] 定义 MVP-0 领域事件：`MeetingCreatedEvent`、`ProcessingTaskCreatedEvent`、`ProcessingTaskStepChangedEvent`、`WorkerPhaseCompletedEvent`。

### 工程：`apps/meeting-api-app`

- [x] 将 `MeetingApplicationService` 从内存 demo 升级为真实应用用例：权限上下文、tenant context、idempotency、outbox 同事务。
- [x] 实现创建 processing task 用例：`AUDIO_UPLOAD` Java-owned step 标记 `SUCCEEDED`，worker `pipelineSteps` 只包含 worker-owned step。
- [x] 实现 outbox 写入与 `ProcessingTaskCreatedEvent`，消息 payload 符合 `processing-task-message.schema.json`。
- [x] 实现 callback 应用服务：HMAC、timestamp、nonce、attempt、lease、tenant / meeting 关系、幂等 body hash。
- [x] 实现 heartbeat 分支：`RUNNING && progress > 0` 不写 `callback_events`，latest-wins 更新 progress / heartbeat / lease。
- [x] 实现 `/complete phase=WORKER_DAG` 只推进 `phase=WORKER_DAG_DONE` 并写 `WORKER_PHASE_COMPLETED`，不直接把 task 置为 `SUCCEEDED`。

### 工程：`apps/meeting-api-infrastructure`

- [x] 替换 `InMemoryMeetingRepository` 为 PostgreSQL repository，至少覆盖 meetings、meeting_participants、processing_tasks、processing_task_steps、callback_events、domain_events_outbox。
- [x] 实现事务开始设置 `app.tenant_id` / `app.user_id` / `app.request_id`，事务结束 reset tenant context。
- [x] 实现 outbox publisher：`FOR UPDATE SKIP LOCKED`、批量 100、失败重试、单聚合 `sequence_no` 顺序。
- [x] 实现 RabbitMQ publisher，投递 task message 到一期队列并携带 `taskId`、`tenantId`、`traceId`。

### 工程：`apps/meeting-api-adapter`

- [x] 实现 `/api/auth/login`、`/api/auth/logout`、`/api/auth/me` 的内置账号 MVP。
- [x] 实现 `/api/meetings` 创建 / 列表 / 详情，去掉 tenant header 伪上下文，改由登录态设置 tenant context。
- [x] 实现 `/api/meetings/{meetingId}/processing-tasks`、`GET /api/processing-tasks/{taskId}`、retry、cancel。
- [x] 实现 `/api/processing-tasks/{taskId}/events` SSE：建连先发 snapshot，支持 `Last-Event-Id`，不可续接时回退当前 snapshot。
- [x] 将 `ProcessingTaskCallbackController` 从 accepted stub 改为读取完整 headers、原始 URI、body 并调用 app command。

### 工程：`apps/ai-worker`

- [x] 实现 RabbitMQ consumer / WorkerRuntime MVP，消费 `MEETING_FULL_PIPELINE` task message。
- [x] 实现 fake / smoke workflow：按 registry step 顺序回写 step RUNNING / SUCCEEDED、transcript smoke payload、complete phase=`WORKER_DAG`。
- [x] 实现 callback retry：网络错误重试，409 停止重试并记录 `WRITEBACK_FAILED`。
- [x] 实现 `/internal/workflows/{task_id}` 返回 fake workflow 状态，便于联调排查。

### 工程：`apps/meeting-web`

- [x] 实现 LoginPage，接入 `/api/auth/login`，处理 AUTH_REQUIRED、账号锁定、密码错误和服务不可用。
- [x] 实现 MeetingListPage 和 MeetingCreatePage，支持创建会议并跳转详情。
- [x] 增加任务进度页面路由 `/meetings/:meetingId/tasks/:taskId`，展示 task status、phase、step、progress、errorCode、retryable。
- [x] 实现 SSE client：支持 `Last-Event-Id`，重连失败后轮询 `GET /api/processing-tasks/{taskId}`。
- [x] 修正路由命名与 spec：声纹档案使用 `/speaker-profiles`，导出入口使用 `/meetings/:meetingId/exports`。

## 阶段 2：音频上传与真实 Worker Pipeline

> **本阶段收尾状态（2026-05-14）：** MVP 链路、上传/转录/任务进度前端、callback 链路、lease scanner、SSE→轮询恢复、阶段二指标骨架均已落地；ai-worker 仍是可替换 runtime 的 fake pipeline，真实模型 runtime 留待阶段二 +。详见 [本节末 _阶段二收尾备忘_](#阶段-2-收尾备忘2026-05-14)。

### 工程：`apps/meeting-api`

- [x] 实现音频 multipart upload session：8 MiB 默认分片、10000 part 上限、24h TTL、part sha256 去重、complete 校验全文件 sha256。
- [x] 实现 `meeting_files` 持久化和 TOS / MinIO 签名 URL 生成，原始音频落 `meeting-audio` 前缀。
- [x] 实现音频 complete 后创建 `MEETING_FULL_PIPELINE` task，并同步会议状态 `CREATED -> PROCESSING`。
- [x] 实现 task lease scanner：lease 过期置 `ORPHANED`，可重新入队，旧 attempt callback 不覆盖新 attempt。
- [x] 实现 transcript callback 落库：`original_text`、`edited_text`、`current_text`、speaker label、timestamp precision、版本号。

### 工程：`apps/ai-worker`

- [x] 接入 ArtifactStore / TOS 客户端，支持读取音频、写入质量报告、ASR 原始 JSON、diarization turns、artifact manifest。
- [x] 实现 `AUDIO_PREPROCESS`：ffprobe、4 小时上限、采样率低于 16kHz reject、channel_map、质量告警。
- [x] 实现 VAD 与 ASR 切片策略：默认 60s、范围 30-120s、overlap 默认 0.5s、记录 chunk strategy / pipeline version。
- [ ] **真实 ASR model runtime**（fake 实现已落地，真实模型留待阶段二 +；见上文备忘）。
- [ ] **真实 diarization model runtime**（同上，fake 实现已落地）。
- [x] 实现 `TRANSCRIPT_MERGE`，输出结构化 segment 并 callback Java。

### 工程：`apps/meeting-web`

- [x] 实现音频上传页面 `/meetings/:meetingId/audio`，支持分片上传、并发数 1-5、part 重试最多 3 次、取消 / 重试。
- [x] 实现任务进度 step 展示，不只展示线性百分比；区分 worker step 与 Java-owned step。
- [x] 实现转录查看页面，支持分页 / 虚拟滚动，默认使用 `currentText`。

### 阶段 2 收尾备忘（2026-05-14）

#### 已落地

| 项 | 位置 | 备注 |
|---|---|---|
| 音频 multipart upload | `apps/meeting-api` `AudioUpload*` | 8 MiB 默认分片、part sha256 去重、complete 全文件校验 |
| `MEETING_FULL_PIPELINE` 任务创建 + 状态转换 | `ProcessingTaskApplicationService#createForCompletedAudioUpload` | 同步会议状态 `CREATED → PROCESSING` |
| ai-worker fake pipeline | `apps/ai-worker` workflow registry | ASR / diarization 是可替换 runtime 的本地实现，**未接真实模型** |
| transcript callback 落库 | `ProcessingTaskCallbackApplicationService#writeTranscript` + `transcript_segments` 表 | 含 `original_text` / `edited_text` / `current_text` / 版本号 |
| Web 音频上传页面 | `apps/meeting-web/src/features/audio/AudioUploadPage.tsx` | 含分片并发、恢复、跨刷新、sha256 校验 |
| Web 任务进度 step 展示 | `apps/meeting-web/src/features/tasks/TaskProgressPage.tsx` | 含 SSE/轮询切换、终止态停止轮询 |
| Web 转录页面 | `apps/meeting-web/src/features/transcript/TranscriptPage.tsx` | 含 segment 排序、`currentText` 默认 |
| `GET /api/meetings/{meetingId}/processing-tasks/latest` | `ProcessingTaskController#getLatestForMeeting` | Web 上传完成后跳转用 |
| Lease scanner | `ProcessingTaskLeaseScanner` + `ProcessingTaskLeaseScannerConfig` | `@Scheduled` 默认每 30s 扫一次，可通过 `meeting.lease-scanner.enabled=false` 关闭 |
| 老 attempt callback 拒绝 | `ProcessingTask#validateCallback` | `attemptNo` / `leaseOwner` 双重校验，覆盖在 `ProcessingTaskDomainTest#staleAttemptAndLeaseCannotUpdateCurrentTask` |
| Metrics 骨架 | `MeetingApiMetrics` | callback / SSE / outbox / lease scanner counter，Spring Boot Actuator `http.server.requests` 默认开启 |

#### 阶段二 +（仍未做，需要先决策）

- [ ] ai-worker 真实模型 runtime（Qwen3-ASR / pyannote / CAM++）——一旦决定要做，需要：模型权重内网制品路径、`docs/model-registry.md` 增加 checksum、`POST /internal/models` 暴露模型版本、production 配置禁联网下载（阶段 8 任务）。
- [ ] 多租户 callback 鉴权完整 fuzz：HMAC / timestamp skew / nonce / idempotency / attempt / lease / tenant 链接 7 项联合压测。
- [ ] Playwright / Cypress 端到端：登录 → 上传 → 任务进度 SSE → 转录展示。当前本节末提供手工 E2E 清单替代。

#### 手工 E2E 走查清单（用于阶段三开工前验收）

> 顺序执行；每步出现的命令以仓库根为工作目录。失败立即停下并诊断，不要继续。

| 步骤 | 命令 / 操作 | 期望结果 |
|---|---|---|
| 1 | `cp .env.example .env`（一次性） | `.env` 内含 HMAC、MinIO、RabbitMQ 占位值 |
| 2 | `docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d` | PostgreSQL / RabbitMQ / MinIO / Vault-dev / Prometheus / Grafana / Loki 全部 `healthy` |
| 3 | `cd apps/meeting-api && export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./mvnw spring-boot:run -pl meeting-api-start -am` | Flyway 自动 migrate 至最新，端口 `:8080` listen，`http://localhost:8080/actuator/health` 返回 UP |
| 4 | `cd apps/ai-worker && uv run ai-worker-api` | FastAPI listen `:8090`，`http://localhost:8090/internal/health` 返回 ready |
| 5 | `cd apps/meeting-web && npm run dev` | Vite listen `:5173`，浏览器打开 |
| 6 | Web 登录 mock 凭据 | 跳转 `/meetings`，列表加载成功 |
| 7 | 创建新会议（`INTERNAL`、`zh`） | 跳转会议详情，状态 `CREATED` |
| 8 | 进入 `/meetings/:meetingId/audio`，选择 ≤ 30 秒 WAV | 触发 multipart upload，分片全部 `completed` |
| 9 | 上传完成后自动跳转 `/meetings/:meetingId/tasks/:taskId` | 任务进度页显示 `WORKER_DAG_RUNNING`，SSE 连接为 `SSE`，AUDIO_PREPROCESS / ASR 等 step 依序变化 |
| 10 | 等待 fake pipeline 全部 `SUCCEEDED` | 任务进度连接显示 `已结束`，任务状态 `SUCCEEDED` 或 `PARTIAL_SUCCEEDED` |
| 11 | 进入 `/meetings/:meetingId/transcript` | 至少 1 个 segment，`speakerLabel` 为 `SPEAKER_00`，`currentText` 非空 |
| 12 | Prometheus 抓取 `:8080/actuator/prometheus` | 含 `meeting_api_callback_events_total`、`meeting_api_outbox_published_total`、`meeting_api_sse_opened_total`、`meeting_api_lease_scanner_runs_total` |
| 13 | 手动 kill worker → 等待 lease TTL（5 分钟） | 任务状态从 `RUNNING` 转 `ORPHANED`，scanner 日志 `lease_expired task=...` |

完成所有步骤即视为阶段二可发版到阶段三开工前的验收门禁。Playwright 自动化版本后续在阶段 8 任务里补齐。

## 阶段 3：Java LLM、纪要、事项与 STALE

### 工程：`apps/meeting-api`

- [x] 实现 `TaskStepProgressService`，由 Java 推进 `SUMMARY` / `EXTRACTION`，source 固定 `JAVA_TASK_SERVICE`。
- [x] 实现 `WORKER_PHASE_COMPLETED` listener：`MEETING_FULL_PIPELINE` 进入 `JAVA_LLM_RUNNING`，非 LLM worker task 直接进入 `TERMINAL`。
- [x] 实现 DashScope `LlmGateway`：OpenAI-compatible、security level fail closed、prompt template、input/output hash、token、latency、schema 校验和审计。
- [x] 实现会议纪要生成，保存 `meeting_minutes`、evidence segment、`evidence_text_snapshot`、artifact manifest。
- [x] 实现待办、决策、风险抽取；AI 建议与用户确认后的业务事实分离。
- [x] 实现转录编辑：版本冲突校验，保留 `original_text`，更新 `edited_text` / `current_text`，将纪要、事项、RAG chunk、导出标记 STALE。
- [x] 实现 `CONFIDENTIAL` / `SECRET` 自动 LLM 返回 `SECURITY_LEVEL_BLOCKED`。

### 工程：`apps/meeting-web`

- [x] 实现转录编辑、版本冲突提示和下游 STALE 提示。
- [x] 实现纪要页：章节、evidence、重生成、LLM 阻断提示。
- [x] 实现待办 / 决策 / 风险页面，支持接受、拒绝、编辑、evidence 展示。
- [x] 实现 `SECURITY_LEVEL_BLOCKED` 固定业务提示：`一期不支持该安全等级的自动 LLM 处理`。

## 阶段 4：声纹注册、匹配与人工确认

### 工程：`apps/meeting-api`

- [x] 实现 speaker profile、enrollment、授权、撤销、删除和 audit 领域模型。
- [x] 实现 `SPEAKER_ENROLLMENT` task 创建与 callback 校验，允许 `meetingId=null` 但必须校验 profile / enrollment 归属。
- [x] 实现 speaker embedding KMS 信封加密：AES-256-GCM、12 bytes nonce、16 bytes tag、wrapped DEK、checksum、key version。
- [x] 实现 speaker candidates callback 落库：明文 embedding 只在 internal callback 内短暂存在，成功后不写日志、不进入 public DTO。
- [x] 实现 speaker confirm / reject，更新转录 speaker 显示、RAG chunk freshness 和审计。
- [x] 实现撤销授权级联：新匹配排除 profile，历史 person_id 软屏蔽，相关 RAG chunk 标记 STALE 并异步去标识重建。

### 工程：`apps/ai-worker`

- [x] 接入 speaker embedding model runtime，支持参考音频和会议 speaker label embedding。
- [x] 实现仅在 Java 授权的 knownParticipants / profile 范围内候选匹配，不做全公司搜索。
- [x] speaker-candidates callback 始终携带 `embedding.values`，禁止改为 TOS 明文 artifact。
- [x] callback 成功或重试耗尽后清理进程内 embedding 明文引用。

### 工程：`apps/meeting-web`

- [x] 实现 speaker 确认页面：匿名 label、候选人、置信度、确认、拒绝、候选过期提示。
- [x] 实现声纹档案页面：档案、授权、参考音频 enrollment、撤销、删除。
- [x] 确保声纹 embedding、模型原始输出和内部 artifact 不出现在页面、日志、监控 breadcrumb。

## 阶段 5：文档知识库与 RAG

### 工程：`apps/meeting-api`

- [x] 实现文档上传、TOS / MinIO 文件元信息、解析状态、删除、reindex。
- [x] 使用 JVM 文档解析库解析 PDF / DOCX / TXT / Markdown；扫描 PDF 和图片 OCR 返回明确不支持错误。
- [x] 实现 chunk 策略：source type、source version、chunk strategy version、content hash、status、stale_status。
- [x] 实现 pgvector + keyword retrieval + metadata filter + PostgreSQL 权限二次校验。
- [x] 实现 `RerankGateway` 同步调用 ai-worker `/internal/rerank`：HMAC、3s timeout、503 / 5xx 降级、400 / 401 不降级并告警。
- [x] 实现 RAG 答案生成：scope 计算、citation、coverage=`TRANSCRIPT_ONLY|FULL`、query log、LLM log、artifact manifest。
- [x] coverage 从 `TRANSCRIPT_ONLY` 到 `FULL` 时使旧 answer cache 失效。

### 工程：`apps/ai-worker`

- [x] 接入 bge-m3 embedding runtime，支持会议、纪要、事项和文档 chunk embedding callback。
- [x] 接入 bge-reranker-v2-m3 lazy-load，并实现 `/internal/rerank` 真实 rerank。
- [x] 模型加载失败返回稳定错误码，Java 决定是否按规则降级。

### 工程：`apps/meeting-web`

- [x] 实现文档知识库页面：上传、解析状态、扫描 PDF 不支持、reindex、删除。
- [x] 实现 RAG 页面：scope 选择、提问、coverage 标签、citation、无可检索内容、429 限流。
- [x] citation 点击定位到会议 segment / 文档 chunk；权限撤销或音频归档时展示退化状态。

### 阶段 5 收尾备忘（2026-05-18）

#### 已落地

| 项 | 位置 | 备注 |
|---|---|---|
| KnowledgeChunk 聚合 + ChunkStrategy | `meeting-api-domain/.../domain/rag/KnowledgeChunk.java` (fe0960d) | Builder + markEmbedding/markStale 状态机 |
| ChunkingApplicationService | `meeting-api-app/.../app/rag/` (18b03b3) | 会议 + 文档统一切分入口 |
| JdbcKnowledgeChunkRepository | `meeting-api-infrastructure/.../persistence/rag/` (58e2cff) | pgvector + HNSW + tsvector 双通道 |
| EmbeddingTaskDispatcher | `meeting-api-app/.../app/rag/` (18f797c) | 新 chunk 扇出 TEXT_EMBEDDING 任务 |
| ai-worker TEXT_EMBEDDING workflow | `apps/ai-worker/...` (aba7043) | 含 inline chunk content |
| writeEmbeddings callback | (d6ef50e) | 批量 pgvector 持久化 |
| `/api/rag/reindex/{meetings,documents}/{id}` | `meeting-api-adapter/.../rag/RagReindexController.java` (fc25d42) | |
| Vector + keyword + RRF fusion | `meeting-api-domain/.../rag/RrfFusion.java` (79703ab) | |
| RagAuthorizationService（二次过滤） | `meeting-api-app/.../app/rag/RagAuthorizationService.java` (e24928b) | 严格 RLS 兜底 |
| RagQueryApplicationService | `meeting-api-app/.../app/rag/RagQueryApplicationService.java` (d93cf70) | scope → embed → 检索 → 授权 → rerank → LLM → citation |
| `POST /api/rag/query` adapter | (6a99013) | |
| RagAnswerCache (coverage-based eviction) | `InMemoryRagAnswerCache.java` (18594a9) | TRANSCRIPT_ONLY → FULL 跃迁清缓存 |
| bge-m3 embedding runtime | `apps/ai-worker/ai_worker/model_runtime/embedding/bge_m3_runtime.py` (1633988) | fake/real 切换 |
| bge-reranker-v2-m3 runtime | `apps/ai-worker/ai_worker/model_runtime/rerank/bge_reranker_runtime.py` (b9376be) | |
| `/internal/embed` 同步查询 embedding | (e5d45ea) | |
| `/internal/models` + warmup | (4861771) | 含 HMAC |
| Embedding + Rerank gateways | `meeting-api-domain/.../rag/{EmbeddingGateway,RerankGateway}.java` (c9c2cda) | 503/5xx 降级 + 400/401 fail-fast |
| ai-worker warmup on ApplicationReady | (d75a135) | fire-and-forget |
| Document parser | `meeting-api-domain/.../document/DocumentParser.java` (ad08e56) | OCR-unsupported 错误码 |
| Document CRUD + reindex | `meeting-api-app/.../document/DocumentApplicationService.java` (c4a6214) | |
| DocumentsPage | `meeting-web/src/features/documents/DocumentsPage.tsx` (4786114) | |
| RagPage | `meeting-web/src/features/rag/RagPage.tsx` (8a67201) | scope + coverage badge + citations |
| TranscriptPage citation deep-link | `meeting-web/src/features/transcript/TranscriptPage.tsx` (05ffc6f) | 含退化态 |
| artifact_manifests 写入 LLM pipeline | (21ab929) | |

#### 未做（落到 Phase 8 收尾）

- [ ] RAG 拆分计时（`rag_query_phase_duration_seconds{phase=...}`）—— 等 Phase 8.1 一并加
- [ ] RAG 答案 429 限流 —— Phase 8.1 性能基线一并做
- [ ] Playwright E2E 覆盖 RAG 主链路 + citation 跳转 —— Phase 8.7

## 阶段 6：异步导出

### 工程：`packages/meeting-contracts`

- [x] 补齐 export DTO、export job status、短链撤销、STALE 确认相关 schema 和 fixtures。

### 工程：`apps/meeting-api`

- [x] 实现 `export_jobs` 应用用例：创建、列表、详情、取消、短链撤销。
- [x] 导出任务绑定 `minutesVersion`、`transcriptVersion`、`ragVersion`，内容 STALE 时要求确认或先重生成。
- [x] outbox 投递 `export-job-message.schema.json` 到 `export-queue`。 _(`OutboxPublisher.routingKey` 现在为 `ExportJobCreatedEvent` 返回 `task.export`，事件 payload 已含 schema 必填 `traceId` / `createdAt`，b1a7e52)_
- [x] 在 Java 进程内实现 `export-queue` consumer，只做消息适配并调用 app command。 _(`ExportQueueConsumer` + `ExportRenderService` 落地于 b0130a8；短 TX 拆分、retry / DLQ 语义、`failTerminally` 通道都已覆盖)_
- [x] 实现 `ExportGateway`：Markdown、DOCX、PDF，PDF 通过 LibreOffice headless 或等价 runtime。 _(Markdown / DOCX / PDF 全部实现；PDF 经 DOCX → `soffice --headless --convert-to pdf`，可配置 binary 与 timeout，b8c75b4)_
- [x] 导出文件写入 `meeting-exports` 前缀，下载只返回后端签名 URL，短链可撤销。 _(`ExportRenderService` 写 `tenant/{t}/meeting/{m}/export/{e}/file.{ext}` 到 `meeting-exports` bucket；`ExportApplicationService.toDto` 经 `ObjectStorageGateway.presignGet` 生成 downloadUrl，REVOKED / 非 SUCCEEDED 强制 null；b0130a8)_

### 工程：`apps/meeting-web`

- [x] 实现会议导出页面 `/meetings/:meetingId/exports`，支持 Markdown / DOCX / PDF 异步创建、状态、取消、下载、短链撤销。
- [x] 导出入口展示 STALE 提示和版本绑定摘要。

### 工程：`infra/meeting-infra`

- [x] 为 meeting-api 镜像或运行环境补齐 LibreOffice headless 和字体包，并增加 PDF 转换 smoke test。 _(`apps/meeting-api/Dockerfile` 多阶段构建，runtime 安装 libreoffice-core/writer + Noto CJK；compose `full-stack` profile 拉起 meeting-api 容器；`infra/meeting-infra/scripts/export-pdf-smoke.sh` 验证登录 → 创建 → 等待 SUCCEEDED → 下载 → `pdftotext` 校验水印 → revoke；854a85f)_

### 阶段 6 收尾备忘（2026-05-18）

#### 已落地

| 项 | 位置 | 备注 |
|---|---|---|
| Export 契约 6.1 | `packages/meeting-contracts/...` (b083f26) | exportStatus / dataBoundaryMode / type enums + 5 fixtures + 3 error codes |
| ExportJob 聚合 + 状态机 | `meeting-api-domain/.../domain/export/ExportJob.java` (51918c0) | Builder + markRunning/Succeeded/Failed/Cancelled/revoke 状态机 + 17 单元测试 |
| 三个 domain ports | `ExportJobRepository`、`ExportGateway`、`MeetingSnapshotPort` (51918c0) | 含 RenderedFile record 和 MeetingSnapshot 嵌套类型 |
| 3 个 domain events | `ExportJobCreatedEvent`、`ExportJobCompletedEvent`、`ExportDownloadRevokedEvent` (51918c0) | 含 outbox payload map |
| `ExportApplicationService` | `meeting-api-app/.../app/export/` (bcf7276) | create/get/list/cancel/revokeLink + LegalHold check + STALE check + audit log |
| `JdbcExportJobRepository` | `meeting-api-infrastructure/.../persistence/export/` (bcf7276) | cursor 分页 + `FOR UPDATE SKIP LOCKED` claim |
| `JdbcMeetingSnapshotPort` | `meeting-api-infrastructure/.../persistence/export/` (8443b40) | 版本锁定 + STALE 过滤 + 6 个聚合查询 |
| `MarkdownExportGateway` | `meeting-api-infrastructure/.../gateway/export/` (8443b40) | 全章节 + 水印注释 + SHA-256 hex |
| `ExportGatewayRegistry` | (8443b40) | Strategy router by ExportFormat |
| Flyway migration | `V202605180001__export_jobs_render_options.sql` (bcf7276) | render_options_json JSONB |
| `ExportController` | `meeting-api-adapter/.../adapter/export/` (de63dd5) | 5 个路由 + WebMvcTest 8 用例 |
| Web `ExportsPage` | `meeting-web/src/features/exports/ExportsPage.tsx` (6151957) | 创建表单 + 列表 + cancel + revoke + 3s 自刷新 + 5 个 Vitest 用例 |
| MSW handlers + 错误码 | `meeting-web/src/shared/api/...` (6151957) | 4 个 export handlers + 3 个新错误码文案 |

#### 未做（明确归档）

- [ ] Testcontainers `JdbcExportJobRepositoryIT` —— RLS + 跨租户隔离 + claimByStatus 锁互斥；与 ExportQueueConsumerIT（RabbitMQ + MinIO + PG 全 Testcontainer）合并到 Phase 8 集成测试一并落地

#### 阶段 6 收尾增量（2026-05-18）

| 项 | 位置 | 备注 |
|---|---|---|
| `PdfExportGateway` | `meeting-api-infrastructure/.../gateway/export/PdfExportGateway.java` (b8c75b4) | DOCX → `soffice --headless --convert-to pdf`；可配置 binary + timeout；2 个单元测试（supportedFormat、missing binary fail-fast） |
| `ExportJobCreatedEvent` payload schema 合规 | `meeting-api-domain/.../export/ExportJobCreatedEvent.java` (b1a7e52) | 增加 `traceId` (来自 cmd.requestId) + `createdAt`，匹配 `export-job-message.schema.json` |
| `OutboxPublisher` 路由 | `meeting-api-infrastructure/.../mq/OutboxPublisher.java` (b1a7e52) | `ExportJobCreatedEvent → task.export`，命中已绑定的 `export-queue` |
| `ExportRenderService` | `meeting-api-app/.../app/export/ExportRenderService.java` (b0130a8) | 三段短 TX：markRunning → render+upload → markSucceeded；`failTerminally` 通道；ExportInputInvalid 标 FAILED + 抛出，ExportRuntime 维持 RUNNING 让 broker 重试；6 个单元测试 |
| `ExportQueueConsumer` | `meeting-api-infrastructure/.../mq/ExportQueueConsumer.java` (b0130a8) | bare-RabbitMQ-client `@Component`，opt-in via `meeting.export.consumer.enabled`，5 个 onMessage 单元测试 |
| `ObjectStorageGateway.putObject` | `meeting-api-domain/.../storage/ObjectStorageGateway.java` (b0130a8) | 新端口 + `LocalObjectStorageGateway` 实现（可写本地 `meeting.storage.local-root`） |
| downloadUrl 签名 | `ExportApplicationService.toDto` (b0130a8) | SUCCEEDED + 未 revoked + meeting_files 行存在时经 `presignGet` 注入；REVOKED 强制 null |
| `EXPORT_STATUS_CHANGED` 加入 `taskEventType` | `enums.yaml` + `public-api.yaml` (7951910) | 契约就位；live SSE emitter 暂未接，前端继续 3s 轮询 |
| meeting-api Dockerfile | `apps/meeting-api/Dockerfile` (854a85f) | 多阶段 + LibreOffice writer + Noto CJK，目标 < 1.5 GB |
| compose full-stack profile | `infra/meeting-infra/docker/compose/docker-compose.yml` (854a85f) | 新增 `meeting-api` service，依赖 postgres/rabbitmq/minio |
| PDF smoke 脚本 | `infra/meeting-infra/scripts/export-pdf-smoke.sh` (854a85f) | login → create → 轮询 SUCCEEDED → 下载 → pdftotext 校验水印 → revoke 检查 |

#### 已知 follow-up

- [ ] 完整的 RabbitMQ + MinIO + PG Testcontainers IT（`ExportQueueConsumerIT`）——一旦 Phase 8 集成测试基建到位再补
- [ ] SSE emitter for `EXPORT_STATUS_CHANGED`——若 UX 出现 3s 轮询延迟问题再实现

## 阶段 7：合规、删除、legal hold 与 break-glass

### 工程：`apps/meeting-api`

- [x] 实现 legal hold 创建、释放、命中阻断和 audit event。
- [x] 实现 deletion job：计划生成、执行锁、legal hold 二次检查、对象删除 / 生命周期标记、失败项摘要。 _(`DeletionJobRunner` + 5 个 executor + KMS destroyer 落地，4210714 / e28d18b)_
- [x] 实现 deletion certificate：对象 hash、范围、执行人、时间、失败项和审计摘要。 _(`DeletionCertificate` 聚合 + GET 端点，7bb43b7)_
- [ ] 删除任务只有全部目标处理成功时才推进 meeting `DELETED`；失败或 legal hold 命中保持原状态。 _(Meeting delete 端点本身尚未实现；先 freeze 该交互直到 meeting CRUD 完整)_
- [x] 实现 break-glass：reason、审批人、时间窗口、审批 / 拒绝、审计。 _(`BreakGlassRequest` + 评估端口 + access guard + expiry scanner，b8ebdd3 / 03f9f0b / 9341078)_
- [x] 实现 audit 查询与导出，覆盖处理、查看、导出、权限、声纹访问、break-glass。 _(`AuditEventController` + `AuditEventsPage`，0955a36)_

### 工程：`apps/meeting-web`

- [x] 实现 legal hold 管理页面。
- [x] 实现 deletion jobs 和 deletion certificate 页面。 _(`DeletionJobsPage` + 错误码扩展，d661d2e)_
- [x] 实现 break-glass 申请、审批、拒绝和审计页面。 _(`BreakGlassPage` + 4 个 MSW handler，f7eabb5)_
- [x] 合规页面所有写操作按后端权限和稳定错误码控制，不以前端隐藏作为安全边界。 _(全部 4 个 admin 页统一走错误码 + 后端校验)_

### 工程：`infra/meeting-infra`

- [ ] 增加 legal hold 下生命周期清理不会删除受保护对象的部署 / 运维 smoke test。
- [x] 补齐备份恢复 runbook：PostgreSQL RPO 5min / RTO 30min、对象 hash 校验、RabbitMQ 依赖 outbox 重放。 _(`docs/runbooks/backup-recovery.md` + `docs/runbooks/legal-hold-procedure.md`，d651bd9)_

## 阶段 8：观测、安全、性能与发布

### 工程：`apps/meeting-api`

- [x] 为 public endpoint、callback endpoint、outbox publisher、SSE emitter 增加 Micrometer timer / counter。
- [x] 实现健康检查：PostgreSQL、RLS tenant smoke、RabbitMQ、TOS / MinIO、outbox、KMS、必要队列、ai-worker rerank。 _(`PostgresRls`、`RabbitMqQueue`、`MinIo`、`Kms`、`AiWorker`、`OutboxBacklog` HealthIndicator，1fb23dc)_
- [x] prod profile fail-fast：缺少 HMAC、chunk strategy、ai-worker base URL / HMAC / rerank model、RLS 关闭、CONFIDENTIAL / SECRET 误允许 LLM。 _(`ProdProfileValidator` + 9 个单元测试，8e12ee8)_
- [ ] 增加性能测试与告警指标：meeting list p95、callback p95、outbox lag、SSE 首字节、RAG p95。 _(Prometheus rules 12 条已落地；剩 p95 性能测试基线脚本)_

### 工程：`apps/ai-worker`

- [x] 实现 `GET /internal/models` 返回模型版本、checksum、device、状态、最近错误。
- [x] 增加 GPU 指标、RTF、step 失败率、OOM 退出策略。 _(`ai_worker/observability/gpu_metrics.py` 暴露 6 个 Prometheus surface + `report_oom_and_exit` 退出 137，f120e12)_
- [x] 生产启动禁止联网下载模型权重；模型 checksum 不匹配拒绝 ready。 _(Dockerfile 注入 `HF_HUB_OFFLINE=1` / `TRANSFORMERS_OFFLINE=1`；`compute_checksum` 在 `/internal/models` 中纳入响应，3e9f196 + f120e12)_
- [x] 补齐模型准入清单 `docs/model-registry.md` 的 checksum、内网制品路径和审批记录。 _(checksum 计算流程 + 责任人列已落，权重 SHA-256 实际值待真实下载后填，f120e12)_

### 工程：`apps/meeting-web`

- [ ] 增加 CSP / sanitizer / Markdown XSS 测试，RAG answer、纪要、evidence 文本不能直接渲染不可信 HTML。 _(`apps/meeting-web/nginx.conf` ship 了严格 CSP；当前 UI 仅以 `<pre>` 渲染 minutes markdown，无 HTML 注入面；SafeMarkdown 组件等到首次接入 HTML 渲染时再补，f2daf10)_
- [x] 接入前端监控，仅采集 route、error code、requestId、traceId 和浏览器环境，不采集正文、文件名原文或 token。 _(`src/services/telemetry.ts` + 12 个 vitest 断言；allowlist + 一切非标量字段直接 drop，f2daf10)_
- [x] 实现 route-level code split、转录虚拟滚动、长 Markdown 懒加载，控制首屏 JS gzip 预算。 _(`App.tsx` 把所有非核心路由切到 `React.lazy + Suspense`；转录虚拟滚动 / Markdown 懒加载留待真实数据形态稳定后再补，f2daf10)_
- [x] 增加 Playwright E2E：登录 -> 创建会议 -> 上传 -> 任务进度 -> 转录 -> 纪要 -> RAG -> 导出，以及 `SECURITY_LEVEL_BLOCKED` 分支。 _(`apps/meeting-web/e2e/` 框架 + `main-flow.spec.ts` 覆盖 login → create → transcript/export pages 渲染 + CONFIDENTIAL 分支文案；完整 upload → SSE → RAG 流程依赖 ai-worker 真实模型 runtime 上线后再补，PR-V)_

### 工程：`infra/meeting-infra`

- [x] 增加 full-stack compose 或 K8s dev overlay：meeting-api、meeting-web、ai-worker 镜像构建和健康检查。 _(`docker-compose.yml` 多了 `meeting-api` service（profile `full-stack`）；K8s base + overlays/dev 落地，854a85f + 7cb6e9d)_
- [x] 增加 Dockerfile：meeting-api、meeting-web、ai-worker；meeting-api 镜像超过 1.5GB 时重新评估 export runtime 拆分。 _(三份 Dockerfile + 三份 `.dockerignore`；meeting-api 仅装 libreoffice-writer + Noto CJK 保持 < 1.5 GB，3e9f196 + 854a85f)_
- [x] 增加 K8s base / dev overlay：deployment、service、configmap、servicemonitor、GPU node selector、PDB / HPA。 _(`k8s/base/{meeting-api,meeting-web,ai-worker}` + `overlays/{dev,prod}` + `terraform/main.tf` 3 个资源，7cb6e9d)_
- [x] 增加 Prometheus rules：outbox backlog、RabbitMQ DLQ、callback auth fail、RAG rerank 降级、KMS 失败、GPU OOM、export 失败。
- [ ] 确保真实密钥不进入 git，部署只使用 `.env`、K8s Secret 或密钥管理系统注入。 _(K8s manifest 声明 `meeting-api-secret` / `ai-worker-secret` 由 Vault 等外部系统注入；CI 层面的 git-leaks / pre-commit 扫描留待 Phase 9)_

## 持续性工程任务

- [ ] 每个阶段完成后运行 contracts check、Java compile/test、Python pytest、Web type-check/test，并把命令写入对应工程 README。
- [ ] 每个新增业务域先创建符合 SPEC 的 package，再落 Controller / ApplicationService / Aggregate / RepositoryImpl / Gateway。
- [ ] 每次修改字段、枚举、错误码、状态机或 API，先改事实源：Flyway migration、OpenAPI、JSON Schema、common enums / error-codes。
- [ ] 保持 Java 管业务事实和权限、Python 只做 AI Pipeline、Web 只消费 Public API / SSE 的边界。
- [ ] 保持 AI 产物与业务事实分离：重生成只产生 diff / 新建议，不覆盖用户已确认字段。
- [ ] 保持 PUBLIC / INTERNAL 可自动 LLM，CONFIDENTIAL / SECRET 自动 LLM fail closed。
