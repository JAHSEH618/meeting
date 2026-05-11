# meeting-api-domain Spec

## 1. 项目定位

`meeting-api-domain` 是领域层，包含聚合、实体、值对象、领域服务、领域事件、Repository / Gateway 端口和业务不变量。它不依赖 Spring Web、数据库实现、消息队列实现或外部 SDK。

## 2. 包边界

建议包结构：

```text
com.meeting.api.domain
  auth/
  tenant/
  person/
  meeting/
  task/
  storage/
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

## 3. 核心聚合与实体

| 聚合 / 实体 | 关键不变量 |
|---|---|
| `Tenant` | 租户隔离边界，业务对象必须归属单一 tenant |
| `User` | 登录账号、角色和状态，不直接等同现实人员 |
| `Person` | 现实参会人，可与 user 绑定 |
| `Meeting` | 会议安全等级、状态、参会人、版本号；状态机统一为 `CREATED`、`PROCESSING`、`SUCCEEDED`、`FAILED`、`DELETED` |
| `MeetingFile` | 音频和文档文件元信息、TOS URI、hash |
| `ProcessingTask` | task 状态机、phase、attempt、lease、step |
| `TranscriptSegment` | 时间戳、speaker、original/edited/current text |
| `MeetingSpeaker` | 匿名 label 与 person 的人工确认状态 |
| `SpeakerProfile` | 声纹档案、授权、撤销状态 |
| `MeetingMinutes` | 纪要版本、stale status、artifact manifest |
| `ActionItem` | AI 建议与人工确认后的业务待办分离 |
| `Decision` | 决策文本、evidence、状态和 stale status |
| `Risk` | 风险等级、evidence、状态和 stale status |
| `Document` | 文档解析状态、security level、版本 |
| `KnowledgeChunk` | status 与 stale status 分离 |
| `ExportJob` | 格式、输入版本、状态、短链撤销 |
| `LegalHold` | 保全范围、原因、审批人、有效期 |
| `DeletionJob` | 删除范围、legal hold 检查、证书生成 |

聚合边界：

| 聚合根 | 聚合内对象 | 聚合外引用 |
|---|---|---|
| `Meeting` | `MeetingParticipant`、`MeetingFile`、会议基础版本字段 | `TranscriptSegment`、`MeetingMinutes`、`ActionItem`、`Decision`、`Risk` 只通过 `meetingId` 引用 |
| `ProcessingTask` | `ProcessingTaskStep`、attempt、lease、step progress | artifact、transcript、speaker candidates 通过 `artifactManifestId` / `meetingId` 引用 |
| `TranscriptSegment` | 单 segment 的原文、编辑文、speaker label、时间戳 | 不内嵌 Meeting；通过 `meetingId` 和 `transcriptVersion` 关联 |
| `SpeakerProfile` | enrollment、consent、centroid metadata、revocation state | `Person` 通过 `personId` 引用，不跨聚合直接修改 |
| `MeetingMinutes` | sections、evidence snapshot、minutesVersion | action item / decision / risk 是独立聚合或独立表记录 |
| `KnowledgeChunk` | chunk content/hash、embedding metadata、status/staleStatus | meeting/document/source 通过 id 和 version 引用 |
| `ExportJob` | 输入版本、格式、状态、下载链接状态 | meeting / artifact 通过 id 引用 |
| `LegalHold` | 保全范围、审批与释放信息 | 被保全对象不内嵌，只记录 `(scopeType, scopeId)` |
| `DeletionJob` | 删除范围、执行状态、失败项摘要 | certificate 独立记录，通过 job id 关联 |

Repository 只能按聚合根保存聚合；跨聚合一致性由 app 层事务编排和领域事件表达，禁止在单个聚合方法里直接修改另一个聚合。

## 4. 领域规则

### 4.1 AI 产物不等于业务事实

1. AI 生成待办、决策、风险默认是建议。
2. 用户接受后才成为业务事实。
3. 重生成只能产生 diff、新版本或新建议。
4. 已确认字段不得被 AI 静默覆盖。
5. 已同步外部系统的待办 status 不允许被 AI 改写。

### 4.2 版本与 STALE

1. 会议转录有 `transcriptVersion`。
2. 纪要有 `minutesVersion`。
3. RAG chunk 有 `chunkVersion` 和 `sourceVersion`。
4. 内容 freshness 使用 `staleStatus`，不能复用业务 `status`。
5. 重建完成时 expected version 不匹配，不得覆盖当前 ACTIVE 结果。

### 4.3 Task lease

1. Worker 领取任务时设置 `leaseOwner` 和 `leaseExpiresAt`。
2. heartbeat 更新 `heartbeatAt` 和 lease。
3. lease 过期进入 `ORPHANED`。
4. 用户取消先进入 `CANCEL_PENDING`，worker 或 Java 确认停止后进入 `CANCELLED`。
5. optional step 失败但核心产物可用时可进入 `PARTIAL_SUCCEEDED`，并要求对应 step 写入 `SKIPPED` 或 `FAILED` 原因。
6. 重试耗尽进入 `FAILED` 或 DLQ。
7. 旧 attempt callback 不得推进新 attempt 状态。

task 状态机必须覆盖 `PENDING`、`QUEUED`、`RUNNING`、`ORPHANED`、`PARTIAL_SUCCEEDED`、`SUCCEEDED`、`FAILED`、`CANCEL_PENDING`、`CANCELLED`。`ProcessingTask.phase` 独立表达管线阶段，覆盖 `WORKER_DAG_RUNNING`、`WORKER_DAG_DONE`、`JAVA_LLM_RUNNING`、`TERMINAL`。Worker `/complete` callback 必须携带 `phase=WORKER_DAG`，只表示 worker DAG 阶段完成，不允许直接把 task 推进到 `SUCCEEDED`；`PARTIAL_SUCCEEDED` 只允许由 Java app 层在 worker phase partial 或 Java 内部 optional step 策略确认核心产物可用后产生。

phase 迁移规则：

| From | To | 触发 |
|---|---|---|
| `WORKER_DAG_RUNNING` | `WORKER_DAG_DONE` | `/complete phase=WORKER_DAG` callback 幂等落库成功 |
| `WORKER_DAG_DONE` | `JAVA_LLM_RUNNING` | app 层 listener 开始推进 `SUMMARY` |
| `WORKER_DAG_DONE` | `TERMINAL` | task type 无 Java LLM 阶段（`TEXT_EMBEDDING` / `RAG_REINDEX` / `SPEAKER_ENROLLMENT` / `EXPORT`），listener 收到 `WORKER_PHASE_COMPLETED` 后按必做 step 结果直接置 task 终态 |
| `JAVA_LLM_RUNNING` | `TERMINAL` | `SUMMARY` / `EXTRACTION` 与所有必做 step 达到终态 |
| 任意非 `TERMINAL` | `TERMINAL` | task `FAILED`、`CANCELLED` 或 deletion / cleanup 强制终止 |

外部观察者不得仅用 `processing_tasks.status=RUNNING` 推断进度阶段；Public API 的 `ProcessingTaskDTO.phase` 是前端进度条、运维 dashboard 和告警阶段判断的事实来源。

允许迁移边显式包含 status 与 phase 两个维度；worker phase 完成期间 status 维持 `RUNNING`，task status 终态（`SUCCEEDED` / `PARTIAL_SUCCEEDED` / `FAILED` / `CANCELLED`）仅在 phase 推进至 `TERMINAL` 的同一事务中确定。

| From (status, phase) | To (status, phase) | 触发 |
|---|---|---|
| `PENDING`, `WORKER_DAG_RUNNING` | `QUEUED`, `WORKER_DAG_RUNNING` | task 创建后投递 outbox / MQ |
| `QUEUED`, `WORKER_DAG_RUNNING` | `RUNNING`, `WORKER_DAG_RUNNING` | worker 领取或 Java 内部执行器开始处理 |
| `RUNNING`, 任意非 `TERMINAL` | `RUNNING`, phase 不变 | heartbeat / progress update，只刷新 lease 和 step progress |
| `RUNNING`, 任意非 `TERMINAL` | `ORPHANED`, phase 不变 | lease 过期且未进入终态 |
| `ORPHANED`, phase 不变 | `QUEUED`, phase 不变 | Java lease scanner 重新入队 |
| `RUNNING`, `WORKER_DAG_RUNNING` | `RUNNING`, `WORKER_DAG_DONE` | `/complete phase=WORKER_DAG status=SUCCEEDED`；记录 worker phase 完成，但 task 不进入终态 |
| `RUNNING`, `WORKER_DAG_RUNNING` | `RUNNING`, `WORKER_DAG_DONE` | `/complete phase=WORKER_DAG status=PARTIAL_SUCCEEDED` 且携带 `skippedSteps`；写入 optional worker step 的 `SKIPPED` / `FAILED` 原因 |
| `RUNNING`, `WORKER_DAG_DONE` | `RUNNING`, `JAVA_LLM_RUNNING` | `MEETING_FULL_PIPELINE` listener 开始推进 `SUMMARY` |
| `RUNNING`, `WORKER_DAG_DONE` | `SUCCEEDED`, `TERMINAL` | 非 LLM task type 没有 Java 内部 step，且所有必做 step 已成功 |
| `RUNNING`, `JAVA_LLM_RUNNING` | `SUCCEEDED`, `TERMINAL` | Java 内部 `SUMMARY` / `EXTRACTION` 以及所有必做 step 全部成功 |
| `RUNNING`, `WORKER_DAG_DONE` / `JAVA_LLM_RUNNING` | `PARTIAL_SUCCEEDED`, `TERMINAL` | worker phase partial 或 Java optional step 失败，但核心产物可用且所有必做 step 已完成 |
| `RUNNING`, 任意非 `TERMINAL` | `FAILED`, `TERMINAL` | 不可降级 step 失败且重试耗尽 |
| `PENDING` / `QUEUED` / `RUNNING` / `ORPHANED`, 任意非 `TERMINAL` | `CANCEL_PENDING`, phase 不变 | 用户取消请求已接受 |
| `CANCEL_PENDING`, 任意 phase | `CANCELLED`, `TERMINAL` | worker 确认停止，或 Java 确认无有效 lease 且不会再产生新写入 |
| `FAILED` / `PARTIAL_SUCCEEDED`, `TERMINAL` | `QUEUED`, `WORKER_DAG_RUNNING` | 用户重试失败 step 或重建 task |

### 4.4 Meeting 状态

`Meeting` 状态机遵循 F3：

1. `CREATED -> PROCESSING`：音频上传完成并创建处理任务。
2. `PROCESSING -> SUCCEEDED`：必做 step 完成并同步 task 终态。
3. `PROCESSING -> FAILED`：必做 step 失败且重试耗尽。
4. `FAILED -> PROCESSING`：用户重建 / 重试任务。
5. `SUCCEEDED -> PROCESSING`：仅 internal-only 全量 rebuild 允许，一期不开放 public API 或前端入口；局部 RAG reindex 或 `SUMMARY` / `EXTRACTION` regenerate 不改变 meeting status，只改变相关 task / freshness 状态。
6. `任意非 DELETED -> DELETED`：用户删除、retention 或 deletion job，执行前必须检查 legal hold。

### 4.5 声纹

1. 声纹 profile 必须有授权记录。
2. 不做全公司无差别搜索。
3. embedding 不属于可展示业务信息。
4. 撤销授权后，新匹配排除该 profile。
5. 历史转录中的 person id 软屏蔽。
6. 相关 RAG chunk 标记 STALE，并触发去标识重建。

### 4.6 安全等级

1. `PUBLIC` / `INTERNAL` 一期允许自动 LLM。
2. `CONFIDENTIAL` / `SECRET` 一期自动 LLM fail closed。
3. 音频和声纹相关数据永远不得发送第三方 LLM。

### 4.7 Legal Hold 保全范围

1. Legal hold 的范围以 `(entityType, entityId)` 列表表达。
2. 一个 legal hold 可以同时锁定 meeting、meeting file、document、export、artifact、audit event 等多类对象。
3. 删除任务、生命周期清理、声纹撤销级联重建在执行前都必须检查命中的 legal hold。
4. legal hold 创建、解除和命中阻断都必须产生 audit event。

## 5. 领域事件

至少定义：

1. `MeetingCreatedEvent`。
2. `AudioUploadedEvent`。
3. `ProcessingTaskCreatedEvent`。
4. `ProcessingTaskStepChangedEvent`。
5. `WorkerPhaseCompletedEvent`。
6. `TranscriptCompletedEvent`。
7. `TranscriptEditedEvent`。
8. `SpeakerCandidateGeneratedEvent`。
9. `SpeakerConfirmedEvent`。
10. `MinutesGeneratedEvent`。
11. `ContentMarkedStaleEvent`。
12. `RagReindexRequestedEvent`。
13. `ExportRequestedEvent`。
14. `LegalHoldCreatedEvent`。
15. `DeletionJobCompletedEvent`。
16. `BreakGlassApprovedEvent`。

事件由 app 层写入 outbox，domain 只负责表达事件。

所有领域事件必须有统一 envelope：

```json
{
  "eventId": "evt_001",
  "eventType": "TranscriptEditedEvent",
  "aggregateType": "Meeting",
  "aggregateId": "m_001",
  "tenantId": "t_001",
  "sequenceNo": 42,
  "occurredAt": "2026-05-11T06:30:00Z",
  "payloadVersion": "v1",
  "payload": {}
}
```

核心 payload 字段：

| 事件 | payload 必填字段 |
|---|---|
| `ProcessingTaskStepChangedEvent` | `taskId`、`stepName`、`fromStatus`、`toStatus`、`attemptNo`、`progress` |
| `WorkerPhaseCompletedEvent` | `taskId`、`taskType`、`attemptNo`、`workerStatus`、`completedSteps`、`skippedSteps`、`artifactManifestId` |
| `TranscriptEditedEvent` | `meetingId`、`segmentId`、`oldTranscriptVersion`、`newTranscriptVersion`、`editorUserId` |
| `SpeakerConfirmedEvent` | `meetingId`、`speakerLabel`、`personId`、`transcriptVersion`、`source` |
| `ContentMarkedStaleEvent` | `sourceType`、`sourceId`、`oldStaleStatus`、`newStaleStatus`、`reason` |
| `RagReindexRequestedEvent` | `sourceType`、`sourceId`、`expectedVersion`、`chunkStrategyVersion` |
| `DeletionJobCompletedEvent` | `jobId`、`certificateId`、`deletedObjectCount`、`skippedLegalHoldCount` |

## 6. Repository / Gateway 端口

领域层定义端口，不实现端口：

1. `MeetingRepository`。
2. `ProcessingTaskRepository`。
3. `TranscriptRepository`。
4. `SpeakerProfileRepository`。
5. `KnowledgeChunkRepository`。
6. `DocumentRepository`。
7. `ExportJobRepository`。
8. `AuditRepository`。
9. `CallbackEventRepository`：callback event 聚合的幂等记录、重放结果和冲突检测端口；heartbeat 不是 callback event 聚合的一部分，因此不经此 repository，而是通过 `ProcessingTaskRepository` 的进度更新方法刷新 task / step 最新状态。
10. `StorageGateway`。
11. `MessagePublisher`。
12. `LlmGateway`。
13. `EmbeddingGateway`。
14. `ExportGateway`。
15. `KmsGateway`。

## 7. 验收标准

1. 领域层无 Controller、SQL mapper、HTTP client 和 MQ client 实现。
2. 核心状态迁移通过领域方法表达。
3. AI 产物与业务事实分离。
4. STALE 状态与业务 status 分离。
5. Task attempt、lease、heartbeat 不变量在领域层可测试。
6. 声纹撤销级联规则在领域层有明确方法或领域服务。
7. ArchUnit 禁止 domain import `org.springframework.web..`、`org.springframework.jdbc..`、`com.baomidou..`、`com.rabbitmq..`、TOS / DashScope SDK。
