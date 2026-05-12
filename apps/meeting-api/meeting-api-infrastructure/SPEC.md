# meeting-api-infrastructure Spec

## 1. 项目定位

`meeting-api-infrastructure` 是基础设施层，负责实现领域层端口，包括 PostgreSQL / pgvector、TOS、RabbitMQ、DashScope、LibreOffice、KMS、签名 URL、幂等表和外部网关。

## 2. 包边界

目标包结构（新增外部系统实现必须按端口和业务域落包；MVP-0 至少覆盖 `persistence/meeting`、`persistence/task`、`mq`、`idempotency`、`tenant`、`config`）：

```text
com.meeting.api.infrastructure
  persistence/
    meeting/
    task/
    transcript/
    speaker/
    rag/
    document/
    export/
    compliance/
    audit/
  storage/
  mq/
  llm/
  export/
  kms/
  idempotency/
  tenant/
  config/
```

Provider 适配器可以在目标包下再细分，例如 `storage/tos`；不新增长期顶层 provider 包来绕过端口命名。已有临时占位目录在实现对应端口时应迁移到目标包结构。

## 3. PostgreSQL / pgvector

运行时数据库事实源是 `src/main/resources/db/migration/*.sql` 的 Flyway migration。`docs/ddls/001_initial_schema.sql` 仅作为设计评审起点和历史快照，不作为运行时 schema 的第二份事实源。

要求：

1. 所有租户业务表包含 `tenant_id`。
2. 启用 RLS 和 FORCE RLS。
3. 事务开始时设置 `app.tenant_id`、`app.user_id`、`app.request_id`。
4. 事务结束 reset tenant context。
5. current tenant 缺失时 fail closed。
6. 普通业务查询禁止使用绕过 RLS 的账号。
7. pgvector 只保存文本 chunk embedding，不保存声纹 embedding 明文向量。
8. JSON / JSONB 字段在 Java 中使用强类型 record + Jackson 序列化，MyBatis 通过统一 `TypeHandler` 处理；除开放 metadata 字段外，禁止在业务代码中到处传递裸 `Map<String, Object>`。
9. pgvector HNSW 索引参数默认 `m=16`、`ef_construction=64`；查询设置 `ef_search=80`，与 `docs/spec.md` §9.5 的检索默认值保持一致。

必须实现的核心表访问：

1. meetings、meeting_files、meeting_participants。
2. processing_tasks、processing_task_steps、callback_events。
3. transcript_segments、transcript_change_events、meeting_speakers。
4. speaker_profiles、speaker_enrollments、speaker_embeddings。
5. meeting_minutes、meeting_action_items、meeting_decisions、meeting_risks。
6. documents、document_chunks、knowledge_chunks、knowledge_chunk_acl。
7. rag_query_logs、export_jobs、llm_call_logs、prompt_templates。
8. artifact_manifests、audit_events、domain_events_outbox。
9. deletion_jobs、deletion_certificates、legal_holds。

## 4. TOS Storage

实现 `StorageGateway`：

1. 创建 multipart upload。
2. 生成 part upload URL。
3. complete upload。
4. abort upload。
5. 生成下载签名 URL。
6. 写入 artifact JSON。
7. 读取 artifact JSON。
8. 删除或标记生命周期清理对象。

要求：

1. TOS URI 使用 `tos://bucket/key`。
2. 保存 sha256、size、content type、security level。
3. 删除前检查 legal hold。
4. 原始音频、标准化音频、中间 artifact、导出文件分前缀。

## 5. RabbitMQ

实现消息发布和 outbox publisher：

1. 业务事务只写 `domain_events_outbox`。
2. publisher 后台扫描 outbox 并投递 RabbitMQ，publisher 实现在 infrastructure，app 层只负责编排启停和写出 outbox。
3. 发布成功后标记 published。
4. 发布失败记录 `OUTBOX_PUBLISH_FAILED` 并重试。
5. 消息必须包含 `taskId`、`tenantId`、`traceId`。
6. 同一聚合按 `(aggregate_type, aggregate_id, sequence_no)` 单调递增发布；跨聚合可以并发。
7. `export-queue` 的消费入口在 adapter，导出渲染和 TOS 写入能力由 infrastructure 的 `ExportGateway` 提供。

outbox 写入端必须在同一业务事务内为同一聚合分配单调 `sequence_no`。一期采用 `SELECT ... FOR UPDATE` 锁住当前 `(tenant_id, aggregate_type, aggregate_id)` 最新 outbox 行后取最大 `sequence_no + 1`；无历史行时从 1 开始。跨聚合不共享锁，可以并发写入。

同一聚合的事件写入预期在每会议任务级别串行，SSE / task 事件密度有限，因此一期接受该锁作为单聚合序列化点。若后续某类聚合事件密度上升并形成热点，应迁移为 per-aggregate sequence 表，通过 `UPDATE ... RETURNING` 分配序号，避免扫描和锁最新 outbox 行。

outbox publisher 策略：

1. 后台 `ScheduledExecutorService` 或 Spring scheduler 固定 `500ms` 轮询。
2. 单批最多 `100` 条。
3. 使用 `SELECT ... FOR UPDATE SKIP LOCKED` 支持多实例并发。
4. 投递成功后在同一短事务内标记 `published_at=now()`。
5. 失败记录 `last_error_code`、`last_error_message`、`retry_count`。
6. `retry_count >= 5` 后进入 `outbox_dlq` 或在 `domain_events_outbox` 标记 DLQ 状态，等待人工处理。

一期队列：

1. `audio-cpu-queue`。
2. `gpu-asr-queue`。
3. `gpu-diar-queue`。
4. `gpu-speaker-queue`。
5. `embed-queue`。
6. `llm-queue`。
7. `export-queue`。

## 6. DashScope LLM Gateway

实现 `LlmGateway`：

1. OpenAI-compatible protocol。
2. provider 配置来自 application.yml 和环境变量。
3. 请求前校验 security level。
4. 记录 prompt template id、prompt version、provider、configured model、actual model version。
5. 记录 input hash、output hash、latency、token usage。
6. 结构化输出做 JSON schema 校验。
7. evidence 校验失败返回 `LLM_EVIDENCE_INVALID`。
8. schema 失败返回 `LLM_SCHEMA_INVALID`。
9. provider 限流返回 `LLM_RATE_LIMIT`。

Prompt template 加载：

1. 默认从 classpath `prompts/{templateId}/{version}.json` 加载。
2. 如果 `prompt_templates` 表存在同名启用版本，数据库版本优先；每次调用记录实际 `promptTemplateVersion`。
3. template 文件必须包含 `inputSchema`、`outputSchema`、`maxInputTokens`、`modelParams` 和 `dataBoundaryPolicyVersion`。
4. schema 校验失败不得调用 provider。

`llm_call_logs.textRedactionBeforeThirdPartyLlm` 是契约固定审计字段，语义为发送至第三方 LLM 前是否做过文本脱敏；一期恒为 `false`。infrastructure 不得把它实现成可打开的脱敏开关；二期开启脱敏 pipeline 时由 `llm-gateway` 写入 `true`，并必须先升级 contracts 和审计语义。

不得发送原始音频、标准化音频、声纹参考音频、声纹 embedding 和高敏会议文本。

## 7. AI Worker Rerank Gateway

实现 `RerankGateway`：

1. 只由 `meeting-api-app` 的 RAG 查询用例调用，不对前端暴露。
2. 使用内网 + HMAC 调用 `ai-worker` `POST /internal/rerank`，请求 / 响应 schema 以 `packages/meeting-contracts/openapi/ai-worker-internal-api.yaml` 为准。
3. 只发送 Java 已完成权限二次校验、`status=ACTIVE AND stale_status=ACTIVE` 的候选 chunk。
4. HMAC 使用 `meeting.ai-worker.hmac-secret`，不得复用 ai-worker -> Java callback HMAC secret。
5. 请求必须携带 `tenantId`、`requestId`、`traceId`、query 文本、候选 chunk id / source type / text snapshot / RRF score，以及来自 `meeting.ai-worker.rerank.model-version` 的 `modelVersion`。
6. 超时默认 `3s`，由 `meeting.ai-worker.rerank.timeout-ms` 配置；超时、503 或 ai-worker 5xx 时记录 `RERANK_UNAVAILABLE`，是否降级为 RRF 排序由 app 层策略决定并写入 `rag_query_logs`。
7. 400 / 401 视为契约破坏或 HMAC 配置错误，记录 `RERANK_CONTRACT_ERROR`，不降级为 RRF，触发告警并让 RAG query 返回 502。
8. 不通过 RabbitMQ，不创建独立 `rerank-queue`。

## 8. Export Runtime

实现 `ExportGateway`：

1. Markdown 导出。
2. DOCX 导出。
3. PDF 导出。
4. 一期由 `meeting-api` Java 进程内消费 `export-queue`，PDF 可通过 LibreOffice headless 子进程转换。
5. 导出结果写入 TOS。
6. 导出绑定输入版本。
7. 短链可撤销。

导出失败返回 `EXPORT_FAILED`，并保留失败日志摘要。

## 9. KMS 与声纹 embedding

要求：

1. KMS 凭证只部署在 `meeting-api` 一侧，`ai-worker` 不持有 KMS 凭证。
2. Java 收到 speaker embedding 明文 callback 后，立即为每条 embedding 生成 data key 并执行应用层加密。
3. data key 由 KMS master key 包裹。
4. 数据库不存明文 float 数组。
5. 不建立明文 pgvector 索引。
6. KMS 不可用返回 `KMS_KEY_UNAVAILABLE`。
7. 加解密操作写 audit event。

KMS provider：

1. local / test 使用 `software-kms`：本地文件保存 master key，仅用于开发测试。
2. staging / prod 使用火山 KMS 或 HashiCorp Vault，通过 `KmsGateway` 端口隔离。
3. 密钥轮换默认 `90d`；新写入使用最新 key version，历史数据读时按记录的 `kms_key_version` 解密。
4. `ai-worker` 不持有 KMS 凭证。

## 10. 幂等与 callback event

需要持久化：

1. callback event id。
2. idempotency key。
3. request hash。
4. response hash 或处理结果摘要。
5. task id、attempt、lease owner。
6. 状态和错误码。

相同 idempotency key 且 payload 一致时返回已处理结果；payload 不一致返回 `CALLBACK_IDEMPOTENCY_CONFLICT`。

`callback_events` 默认保留 `30d`。表结构必须包含 `request_body_hash`、`response_body`、`http_status`、`processed_at`，支持相同 key 直接重放缓存 body。

`PATCH .../steps/{stepName}` 中 `status=RUNNING && progress>0` 的 heartbeat 不写 `callback_events`，不占用幂等 key 唯一约束；只更新 task / step 最新进度和 lease 时间。其它 callback 仍必须持久化幂等事件。

`processing_tasks.phase` 使用 `task_phase` enum，取值为 `WORKER_DAG_RUNNING`、`WORKER_DAG_DONE`、`JAVA_LLM_RUNNING`、`TERMINAL`。Repository 更新 status 时必须同时维护 phase：worker `/complete phase=WORKER_DAG` 后置为 `WORKER_DAG_DONE`，Java LLM listener 开始时置为 `JAVA_LLM_RUNNING`，任何 task 终态置为 `TERMINAL`。

## 11. 验收标准

1. Repository 实现通过 RLS 测试，跨租户查询返回空或被拒绝。
2. 连接池复用不泄露 tenant context。
3. RabbitMQ outbox publisher 可重试，失败不丢事件。
4. DashScope 调用完整记录审计字段。
5. TOS 上传、下载签名、artifact 写入可用。
6. export runtime 可生成 Markdown、DOCX、PDF。
7. 声纹 embedding 以密文存储。
8. callback 幂等事件可重放。
