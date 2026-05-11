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
| `Meeting` | 会议安全等级、状态、参会人、版本号 |
| `MeetingFile` | 音频和文档文件元信息、TOS URI、hash |
| `ProcessingTask` | task 状态机、attempt、lease、step |
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
4. 重试耗尽进入 `FAILED` 或 DLQ。
5. 旧 attempt callback 不得推进新 attempt 状态。

### 4.4 声纹

1. 声纹 profile 必须有授权记录。
2. 不做全公司无差别搜索。
3. embedding 不属于可展示业务信息。
4. 撤销授权后，新匹配排除该 profile。
5. 历史转录中的 person id 软屏蔽。
6. 相关 RAG chunk 标记 STALE，并触发去标识重建。

### 4.5 安全等级

1. `PUBLIC` / `INTERNAL` 一期允许自动 LLM。
2. `CONFIDENTIAL` / `SECRET` 一期自动 LLM fail closed。
3. 音频和声纹相关数据永远不得发送第三方 LLM。

### 4.6 Legal Hold 保全范围

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
5. `TranscriptCompletedEvent`。
6. `TranscriptEditedEvent`。
7. `SpeakerCandidateGeneratedEvent`。
8. `SpeakerConfirmedEvent`。
9. `MinutesGeneratedEvent`。
10. `ContentMarkedStaleEvent`。
11. `RagReindexRequestedEvent`。
12. `ExportRequestedEvent`。
13. `LegalHoldCreatedEvent`。
14. `DeletionJobCompletedEvent`。
15. `BreakGlassApprovedEvent`。

事件由 app 层写入 outbox，domain 只负责表达事件。

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
9. `StorageGateway`。
10. `MessagePublisher`。
11. `LlmGateway`。
12. `EmbeddingGateway`。
13. `ExportGateway`。
14. `KmsGateway`。

## 7. 验收标准

1. 领域层无 Controller、SQL mapper、HTTP client 和 MQ client 实现。
2. 核心状态迁移通过领域方法表达。
3. AI 产物与业务事实分离。
4. STALE 状态与业务 status 分离。
5. Task attempt、lease、heartbeat 不变量在领域层可测试。
6. 声纹撤销级联规则在领域层有明确方法或领域服务。
