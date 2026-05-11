# meeting-api-infrastructure Spec

## 1. 项目定位

`meeting-api-infrastructure` 是基础设施层，负责实现领域层端口，包括 PostgreSQL / pgvector、TOS、RabbitMQ、DashScope、LibreOffice、KMS、签名 URL、幂等表和外部网关。

## 2. 包边界

建议包结构：

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

## 3. PostgreSQL / pgvector

要求：

1. 所有租户业务表包含 `tenant_id`。
2. 启用 RLS 和 FORCE RLS。
3. 事务开始时设置 `app.tenant_id`、`app.user_id`、`app.request_id`。
4. 事务结束 reset tenant context。
5. current tenant 缺失时 fail closed。
6. 普通业务查询禁止使用绕过 RLS 的账号。
7. pgvector 只保存文本 chunk embedding，不保存声纹 embedding 明文向量。

必须实现的核心表访问：

1. meetings、meeting_files、meeting_participants。
2. processing_tasks、processing_task_steps、callback_events。
3. transcript_segments、transcript_change_events、meeting_speakers。
4. speaker_profiles、speaker_enrollments、speaker_embeddings。
5. meeting_minutes、meeting_action_items、meeting_decisions、meeting_risks。
6. documents、document_chunks、knowledge_chunks、knowledge_chunk_acl。
7. rag_query_logs、export_jobs、llm_call_logs。
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

不得发送原始音频、标准化音频、声纹参考音频、声纹 embedding 和高敏会议文本。

## 7. Export Runtime

实现 `ExportGateway`：

1. Markdown 导出。
2. DOCX 导出。
3. PDF 导出。
4. 一期由 `meeting-api` Java 进程内消费 `export-queue`，PDF 可通过 LibreOffice headless 子进程转换。
5. 导出结果写入 TOS。
6. 导出绑定输入版本。
7. 短链可撤销。

导出失败返回 `EXPORT_FAILED`，并保留失败日志摘要。

## 8. KMS 与声纹 embedding

要求：

1. KMS 凭证只部署在 `meeting-api` 一侧，`ai-worker` 不持有 KMS 凭证。
2. Java 收到 speaker embedding 明文 callback 后，立即为每条 embedding 生成 data key 并执行应用层加密。
3. data key 由 KMS master key 包裹。
4. 数据库不存明文 float 数组。
5. 不建立明文 pgvector 索引。
6. KMS 不可用返回 `KMS_KEY_UNAVAILABLE`。
7. 加解密操作写 audit event。

## 9. 幂等与 callback event

需要持久化：

1. callback event id。
2. idempotency key。
3. request hash。
4. response hash 或处理结果摘要。
5. task id、attempt、lease owner。
6. 状态和错误码。

相同 idempotency key 且 payload 一致时返回已处理结果；payload 不一致返回 `CALLBACK_IDEMPOTENCY_CONFLICT`。

## 10. 验收标准

1. Repository 实现通过 RLS 测试，跨租户查询返回空或被拒绝。
2. 连接池复用不泄露 tenant context。
3. RabbitMQ outbox publisher 可重试，失败不丢事件。
4. DashScope 调用完整记录审计字段。
5. TOS 上传、下载签名、artifact 写入可用。
6. export runtime 可生成 Markdown、DOCX、PDF。
7. 声纹 embedding 以密文存储。
8. callback 幂等事件可重放。
