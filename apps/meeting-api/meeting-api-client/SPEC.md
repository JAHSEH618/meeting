# meeting-api-client Spec

## 1. 项目定位

`meeting-api-client` 是 `meeting-api` 的契约模块，只放 DTO、Command、Query、Result、Facade、枚举和错误码，不放业务实现、不访问数据库、不调用外部系统。

使用方：

1. `meeting-api-adapter` 作为入参、出参和 Facade 契约。
2. `meeting-api-app` 作为应用服务命令、查询和结果对象。
3. 后续 Java SDK 或内部调用方可复用该模块。

## 2. 包边界

建议包结构：

```text
com.meeting.api.client
  common/
    ApiResponse
    ErrorInfo
    PageResult
    ErrorCode
  enums/
    SecurityLevel
    ProcessingStep
    MeetingStatus
    ProcessingTaskStatus
    ProcessingTaskPhase
    ProcessingStepUpdateSource
    RagAnswerCoverage
    StaleStatus
  internal/
    callback/
      SpeakerEmbeddingCallbackCommand
      EmbeddingBatchCallbackCommand
  auth/
  user/
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
```

约束：

1. DTO 字段使用 Java camelCase，与 JSON camelCase 对齐。
2. 枚举名必须与 `packages/meeting-contracts` 一致。
3. 错误码必须稳定，不能在业务逻辑里临时拼字符串。
4. DTO 不携带 JPA、MyBatis、Spring Web 等基础设施注解，除非是通用校验注解且不会污染契约。
5. Public DTO 不暴露声纹 embedding、声纹模型原始输出和内部密钥字段；internal callback command 只能在 `client/internal` 包中承载明文 embedding。

## 3. 必备契约对象

### 3.1 通用对象

1. `ApiResponse<T>`：统一响应信封。
2. `ErrorInfo`：`code`、`message`、`retryable`、`details`。
3. `PageResult<T>`：`items` 和 cursor page。
4. `RequestContextDTO`：requestId、traceId、tenantId 可选展示字段。

### 3.2 会议

1. `CreateMeetingCommand`。
2. `UpdateMeetingCommand`。
3. `MeetingDTO`。
4. `MeetingListQuery`。
5. `MeetingFacade`。

会议 DTO 至少包含：

1. `meetingId`、`tenantId`、`title`。
2. `securityLevel`。
3. `status`。
4. `audioStatus`。
5. `transcriptVersion`、`minutesVersion`、`ragVersion`。
6. `createdBy`、`createdAt`、`updatedAt`。

### 3.3 任务

1. `ProcessingTaskDTO`。
2. `ProcessingTaskStepDTO`。
3. `CreateProcessingTaskCommand`。
4. `RetryTaskCommand`。
5. `CancelTaskCommand`。
6. `TaskEventDTO`：SSE 事件 DTO，包含 `eventId`、`sequenceNo`、`eventType`、`taskId`、`stepName`、`status`、`progress`、`errorCode`、`emittedAt`。

`ProcessingTaskDTO` 必须表达 `status` 与 `phase` 两个维度：`status` 表达任务调度 / 终态，`phase` 表达管线阶段，取值为 `WORKER_DAG_RUNNING`、`WORKER_DAG_DONE`、`JAVA_LLM_RUNNING`、`TERMINAL`。

必须表达 step 级状态、progress、attempt、lease 摘要、`errorCode`、`retryable` 和非空 `source`。

`ProcessingTaskStepDTO` 必须表达 step 推进来源差异：

1. worker 推进的 step 可包含 `attemptNo`、`leaseOwner`、`workerId`。
2. Java 内部推进的 `SUMMARY` / `EXTRACTION` 允许 `attemptNo`、`leaseOwner`、`workerId` 为空，且通过 `source=JAVA_TASK_SERVICE` 明确来源。
3. worker callback 来源使用 `source=AI_WORKER_CALLBACK`。
4. `source` 字段必填，类型必须使用 `ProcessingStepUpdateSource` 枚举，枚举事实来源为 `schemas/common/enums.yaml`。
5. 前端不得因为 Java 推进 step 缺少 lease 信息而判定数据异常。

### 3.4 转录与 speaker

1. `TranscriptSegmentDTO`。
2. `UpdateTranscriptSegmentCommand`。
3. `SpeakerCandidateDTO`。
4. `ConfirmMeetingSpeakerCommand`。
5. `RejectMeetingSpeakerCommand`。

转录 DTO 必须区分 `originalText`、`editedText`、`currentText`，但前端默认展示 `currentText`。

### 3.5 纪要、事项和 RAG

1. `MeetingMinutesDTO`。
2. `ActionItemDTO`。
3. `DecisionDTO`。
4. `RiskDTO`。
5. `EvidenceDTO`。
6. `RagQueryCommand`。
7. `RagAnswerDTO`。
8. `CitationDTO`。

`RagAnswerDTO` 必须包含必填 `coverage: RagAnswerCoverage`，取值为 `TRANSCRIPT_ONLY` 或 `FULL`；前端 RAG answer cache key 必须包含该字段。

所有 AI 结果必须包含 `staleStatus` 和可选 `artifactManifestId`。

### 3.6 Internal Callback Command

Internal callback 专用命令必须隔离到 `com.meeting.api.client.internal.callback`，不得出现在 Public API DTO 包中：

1. `SpeakerEmbeddingCallbackCommand`：承载 `speaker-candidates` callback 中待 Java KMS 加密的明文 embedding，仅 internal callback 使用。
2. `EmbeddingBatchCallbackCommand`：文本 chunk embedding 回写命令，支持直接向量或 TOS artifact URI。
3. 这些命令可被 adapter 和 app 层复用，但不得经 public facade 暴露给前端。

### 3.7 导出与合规

1. `CreateExportCommand`。
2. `ExportJobDTO`。
3. `LegalHoldDTO`。
4. `DeletionJobDTO`。
5. `DeletionCertificateDTO`。
6. `BreakGlassRequestDTO`。

导出 DTO 必须包含 format、输入版本、状态、下载 URL 摘要、短链撤销状态。

## 4. Facade 约定

Facade 只定义应用层能力，不承载实现：

```text
MeetingFacade
ProcessingTaskFacade
TranscriptFacade
SpeakerFacade
MinutesFacade
DocumentFacade
RagFacade
ExportFacade
ComplianceFacade
```

方法命名按用例表达，例如 `createMeeting`、`retryTask`、`queryRag`、`createExportJob`。

## 5. 验收标准

1. 枚举与 `meeting-contracts` 保持一致。
2. 所有 Public API 入参和出参都有 client 对象承载。
3. 不出现数据库实体、Repository、Gateway 实现类。
4. 不出现业务实现逻辑。
5. 声纹 embedding 不在任何 Public DTO 中暴露；仅 internal callback command 可承载明文并只供 adapter / app 内部使用。
6. 错误码覆盖一期错误码字典。

## 6. 契约生成与一致性

`meeting-api-client` 的 DTO 可以先手写，但必须与 `packages/meeting-contracts` 保持一致。进入正式联调前需要建立生成或校验链路：

1. OpenAPI 生成 Java DTO / interface 到临时目录。
2. 与 `meeting-api-client` 手写 DTO 做字段和枚举一致性测试。
3. CI 校验 `ErrorCode` 枚举覆盖 `schemas/common/error-codes.yaml`。
4. CI 校验 `ProcessingStep`、`ProcessingTaskStatus`、`ProcessingTaskPhase`、`ProcessingStepUpdateSource`、`MeetingStatus`、`RagAnswerCoverage`、`StaleStatus` 与 `schemas/common/enums.yaml` 一致。
5. Public DTO 不得包含 internal callback 专用字段；internal callback command 可以在 `client/internal` 包下隔离。
6. CI 在 Java / TypeScript 标识符语境中执行 `\bTaskStatus\b` 检查，不得再命中旧枚举名；`ProcessingTaskStatus` 是唯一合法类型名。
7. Java 推进 step 时不得留空 `source`；当 `attemptNo=null && leaseOwner=null && workerId=null` 时，DTO assembler 必须输出 `source=JAVA_TASK_SERVICE`。
