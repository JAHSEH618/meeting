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
    TaskStatus
    StaleStatus
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
5. 不暴露声纹 embedding、声纹模型原始输出和内部密钥字段。

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

必须表达 step 级状态、progress、attempt、lease 摘要、`errorCode` 和 `retryable`。

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

所有 AI 结果必须包含 `staleStatus` 和可选 `artifactManifestId`。

### 3.6 导出与合规

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
5. 声纹 embedding 不在任何 DTO 中暴露。
6. 错误码覆盖一期错误码字典。
