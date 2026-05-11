# meeting-api-app Spec

## 1. 项目定位

`meeting-api-app` 是应用层，负责用例编排、事务边界、租户上下文、权限编排、幂等控制、状态机推进和 outbox 发布。它连接 adapter、domain 和 infrastructure 端口，但不写具体 SQL、不调用具体外部 SDK。

## 2. 包边界

建议包结构：

```text
com.meeting.api.app
  auth/
  user/
  meeting/
  storage/
  task/
  transcript/
  speaker/
  minutes/
  document/
  rag/
  export/
  compliance/
  audit/
  common/
```

每个业务域可以包含：

```text
command/
query/
executor/
assembler/
policy/
```

## 3. 通用应用规则

1. 每个写用例定义事务边界。
2. 事务开始前设置 tenant context。
3. 权限校验在状态变更前完成。
4. 状态变更和 `domain_events_outbox` 同事务提交。
5. app 层调用 domain 聚合或领域服务执行核心规则。
6. app 层通过 domain 端口调用 Repository / Gateway。
7. app 层负责幂等键检查和重放结果返回。

幂等重放存储：

1. Public API 写操作使用业务 idempotency 表或业务表唯一键记录 `idempotencyKey`、`requestBodyHash`、`responseBody`、`httpStatus`、`processedAt`。
2. Internal callback 使用 `callback_events`，唯一键 `(tenant_id, idempotency_key)`。
3. 同 key 且 body hash 一致时直接返回缓存响应，不重新执行业务写入。
4. 同 key 但 body hash 不一致返回 `CALLBACK_IDEMPOTENCY_CONFLICT` 或 `IDEMPOTENCY_CONFLICT`。
5. callback event 默认保留 `30d`，保留期内必须可重放。

`PATCH /internal/processing-tasks/{taskId}/steps/{stepName}` 中 `status=RUNNING && progress>0` 的 heartbeat 不写 `callback_events`，也不做 request body hash 冲突判定。app 层仅校验 tenant、attempt、lease owner 后按 latest-wins 更新 `heartbeatAt`、`progress`、`leaseExpiresAt`；首次 `RUNNING(progress=0)`、`SUCCEEDED`、`FAILED` 仍走普通 callback 幂等记录。

### 3.1 TaskStepProgressService 归属

`TaskStepProgressService` 是 app 层 service，用于 Java 内部推进 `SUMMARY` / `EXTRACTION` 等非 worker callback step。它不属于 domain Repository / Gateway 端口，也不绕过领域状态机；实现放在 `meeting-api-app/src/main/java/com/meeting/api/app/task/`，调用 `ProcessingTaskRepository` 读取和保存 task / step，并在同一应用事务内写出 `TASK_STEP_UPDATED` outbox 事件。

## 4. 关键用例

### 4.1 会议创建

1. 校验登录态和租户。
2. 校验安全等级枚举。
3. 创建 meeting 聚合。
4. 保存参会人和初始状态。
5. 记录 audit event。

### 4.2 上传完成与处理任务创建

1. 校验会议访问权限。
2. 校验文件元信息和音频时长上限。
3. 保存 `meeting_files`。
4. 创建 `processing_tasks` 和初始 step。
5. 写 outbox 事件，异步投递 RabbitMQ。
6. 返回 task id。

### 4.3 Callback 处理

1. 校验 HMAC、timestamp、nonce。
2. 校验 idempotency key。
3. 校验 tenant、task 和业务对象关系：`MEETING_FULL_PIPELINE` 要求 `meetingId` 非空且匹配；`TEXT_EMBEDDING` / `RAG_REINDEX` 要求 `meetingId` 与 `documentId` 至少一个非空且归属当前 tenant；`SPEAKER_ENROLLMENT` 允许 `meetingId=null`，但必须校验 speaker profile / enrollment 归属。
4. 校验 attempt 和 lease owner。
5. 校验 expected input version。
6. 根据 endpoint 推进 task step、保存 artifact、保存 transcript、保存 speaker candidates 或记录 worker phase 完成。
7. `POST /complete` 必须校验 `phase=WORKER_DAG`；`status=SUCCEEDED` 只表示 worker DAG 阶段成功，`status=PARTIAL_SUCCEEDED` 只表示 worker phase partial 并写入 `skippedSteps`，两者都不得直接把 task 推进到 `SUCCEEDED`。
8. worker phase callback 成功响应前，app 层必须在同一事务内将 `processing_tasks.phase` 从 `WORKER_DAG_RUNNING` 推进到 `WORKER_DAG_DONE`，并写入 `WorkerPhaseCompletedEvent` / `WORKER_PHASE_COMPLETED` outbox 事件。
9. `WORKER_PHASE_COMPLETED` 由 app 层 listener 异步消费，调用 `TaskStepProgressService` 推进 `SUMMARY`；callback 端不得阻塞等待 LLM 调用完成。
10. listener 开始推进 `SUMMARY` 前将 `processing_tasks.phase` 改为 `JAVA_LLM_RUNNING`；Java 内部 step 全部满足终态规则后，才通过 `TaskStepProgressService` 将 task 推进到 `SUCCEEDED` 或 `PARTIAL_SUCCEEDED`、`phase=TERMINAL`，并发布终态事件。
11. 对重复 callback 返回已处理结果。
12. 对幂等键相同但 payload 不一致返回 `CALLBACK_IDEMPOTENCY_CONFLICT`。
13. heartbeat 类 step update 不进入 callback 幂等表，重复上报不得返回 409。

### 4.4 转录编辑

1. 校验会议访问和编辑权限。
2. 更新 segment 的 `edited_text` 和 `current_text`。
3. 保留 `original_text` 不变。
4. 增加 `transcript_version`。
5. 将纪要、待办、决策、风险和相关 RAG chunk 标记 STALE。
6. 写 outbox 事件触发可选重建。

### 4.5 纪要生成

1. 校验 security level。
2. `PUBLIC` / `INTERNAL` 允许走 DashScope。
3. `CONFIDENTIAL` / `SECRET` 返回 `SECURITY_LEVEL_BLOCKED`。
4. 组装结构化转录和必要上下文。
5. app 层 listener 异步消费 `WORKER_PHASE_COMPLETED` 后，调用 `TaskStepProgressService` 将 task phase 改为 `JAVA_LLM_RUNNING`，再将 `SUMMARY` step 标记为 `RUNNING` 并发布 `TASK_STEP_UPDATED` SSE。
6. 调用 `llm-gateway` 端口生成纪要；成功后将 `SUMMARY` 标记为 `SUCCEEDED`，失败标记为 `FAILED`。
7. 调用 `TaskStepProgressService` 将 `EXTRACTION` step 标记为 `RUNNING` 并发布 `TASK_STEP_UPDATED` SSE。
8. 校验 JSON schema 和 evidence，生成 AI 建议待办、决策和风险；成功后将 `EXTRACTION` 标记为 `SUCCEEDED`，失败标记为 `FAILED`。
9. `SUMMARY` / `EXTRACTION` 推进必须可断点续做：以 `taskId + attemptNo + stepName` 为幂等键，LLM 调用成功后先落库 minutes / extraction artifact 和审计记录，再标记 step `SUCCEEDED`；重启后如果当前 attempt 的结果 artifact 已存在，跳过 LLM 调用并直接补齐 `SUCCEEDED` 和 outbox。
10. Java LLM phase 结束后必须将 `processing_tasks.phase` 置为 `TERMINAL`，并按必做 / optional step 结果把 task status 推进到 `SUCCEEDED`、`PARTIAL_SUCCEEDED` 或 `FAILED`。
11. 已确认业务字段不得被重生成覆盖。

### 4.6 声纹 embedding 落库

1. 接收 `ai-worker` 通过 internal TLS + HMAC callback 回写的 speaker embedding 明文。
2. 按 `speakerEnrollmentId` / `speakerProfileId` 校验 speaker enrollment 和 profile 归属当前 tenant；`SPEAKER_ENROLLMENT` 任务允许 `meetingId=null`，但不允许跨租户或未授权 profile 写入。
3. 校验 task、tenant、attempt、lease、idempotency key 和 artifact manifest。
4. 调用 `KmsGateway` 生成 data key，并用 KMS master key 包裹 data key。
5. 用 data key 加密每条 speaker embedding，只保存密文、wrapped data key、checksum、模型版本和授权关系。
6. 明文 embedding 不写日志、不进入普通 artifact、不返回前端。
7. 加密失败返回 `KMS_KEY_UNAVAILABLE` 或稳定的 speaker embedding 错误码，callback 可按错误码重试。
8. 写入完成后发布 speaker candidate / enrollment 相关 outbox 事件。

### 4.7 RAG 查询

1. 鉴权并计算 allowed scope。
2. 通过 rag repository 做 metadata filter + pgvector retrieval + PostgreSQL `tsvector` / `pg_trgm` keyword retrieval。
3. 检索结果做二次权限和 STALE 校验。
4. 组装上下文和 citation。
5. 根据安全等级调用 LLM 或 fail closed。
6. 保存 query log、LLM log 和 artifact manifest 关联。

### 4.8 导出

1. 校验会议访问和导出权限。
2. 检查内容 STALE 状态。
3. 绑定 `minutesVersion`、`transcriptVersion`、`ragVersion`。
4. 创建 `export_jobs`。
5. 写 outbox 事件，由 infrastructure publisher 投递 `export-queue`。
6. `meeting-api` Java 进程内的 export consumer 领取任务并调用 `ExportGateway`。
7. 文件写入 TOS 后更新状态。
8. 支持取消和短链撤销。

### 4.9 legal hold 与删除

1. 创建 legal hold 时要求 reason、审批人和范围。
2. deletion job 创建前检查 legal hold。
3. 删除完成后生成 deletion certificate。
4. 删除、保全和解除都写 audit event。

### 4.10 Deletion Job 执行

1. 领取 deletion job 后先锁定 job 行并设置执行上下文。
2. 重新检查 legal hold；命中时停止物理删除，将 job 标记为 blocked / failed，并继续生成 certificate 与 audit，不直接推进 meeting 删除状态。
3. 按范围撤销签名 URL、删除或生命周期标记 TOS 对象、清理业务表可删除数据。
4. 生成 deletion certificate，包含被删除对象 hash、范围、执行人、时间和失败项摘要。
5. 无论成败都写 audit event：成功记录 `deletion.completed`，部分失败记录 `deletion.partial_failure` 并附失败摘要，legal hold 阻断记录 `deletion.blocked_by_legal_hold`。`deletion_jobs.status` 按实际结果置为 `SUCCEEDED` / `FAILED`，certificate 始终生成；`meetings.status -> DELETED` 仅在 deletion job `SUCCEEDED` 时执行，其余情况 meeting 保持删除前状态。

## 5. 状态机

app 层负责调用 domain 状态机，禁止 adapter 或 infrastructure 直接改状态。

关键状态：

1. `processing_tasks.status`。
2. `processing_tasks.phase`。
3. `processing_task_steps.status`。
4. 纪要、事项和 chunk 的 `stale_status`。
5. `export_jobs.status`。
6. `deletion_jobs.status`。
7. `meetings.status`：遵循 `CREATED -> PROCESSING -> SUCCEEDED / FAILED -> DELETED`；`FAILED -> PROCESSING` 由重试 / 重建触发，`SUCCEEDED -> PROCESSING` 只允许 internal-only 全量 rebuild 触发，一期不开放 public API 或前端入口；局部 `SUMMARY` regenerate 或 RAG reindex 不改变 meeting status，只更新对应 task、step 和 `stale_status`；任意进入 `DELETED` 前必须检查 legal hold。

## 6. 验收标准

1. 所有写用例有清晰事务边界。
2. tenant context 缺失时 fail closed。
3. callback 幂等重放不产生重复 segment。
4. 旧 attempt callback 不能覆盖新 attempt。
5. 转录编辑触发下游 STALE。
6. outbox 与业务数据同事务提交。
7. CONFIDENTIAL / SECRET 自动 LLM 被阻断。
8. RAG 查询经过权限过滤和二次校验。
9. `SUMMARY` / `EXTRACTION` step 状态和 SSE 事件由 Java app 层可观测地产生。
10. deletion job 仅在所有目标对象处理成功时推进 meeting `DELETED`；失败项必须进入 certificate 摘要并保持 meeting 原状态，legal hold 命中时阻断。
