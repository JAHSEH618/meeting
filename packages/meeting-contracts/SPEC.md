# meeting-contracts Spec

## 1. 工程定位

`meeting-contracts` 是跨工程契约的单一事实来源。它定义 `meeting-web`、`meeting-api`、`ai-worker`、RabbitMQ 消息和通用枚举错误码之间共享的数据格式。

职责：

1. 保存 Public API 的 OpenAPI 契约。
2. 保存 Internal Callback API 的 OpenAPI 契约。
3. 保存 RabbitMQ 消息 JSON Schema。
4. 保存通用枚举和稳定错误码。
5. 为后续 TypeScript、Java、Python SDK 生成提供输入。

## 2. 文件结构

```text
openapi/
  public-api.yaml
  internal-callback-api.yaml
schemas/
  rabbitmq/
    processing-task-message.schema.json
  common/
    enums.yaml
    error-codes.yaml
```

## 3. JSON 约定

1. JSON 字段使用 `camelCase`。
2. ID 字段使用字符串，例如 `tenantId`、`meetingId`、`taskId`。
3. 时间使用 ISO-8601 UTC 字符串。
4. 音频时间戳使用毫秒整型字段，例如 `startMs`、`endMs`。
5. 枚举值使用大写下划线。
6. 置信度范围为 `0.0` 到 `1.0`。
7. 大文本、大数组和模型原始输出优先写 TOS，契约中返回 `artifactUri`、`sha256` 和 summary。

## 4. 通用响应

成功响应：

```json
{
  "success": true,
  "data": {},
  "error": null,
  "requestId": "req_001",
  "traceId": "trace_001"
}
```

失败响应：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SECURITY_LEVEL_BLOCKED",
    "message": "一期不支持该安全等级的自动 LLM 处理",
    "retryable": false,
    "details": {}
  },
  "requestId": "req_001",
  "traceId": "trace_001"
}
```

分页响应必须包含 `items` 和 `page`，`page` 至少包含 `cursor`、`hasMore`、`limit`。

## 5. 枚举

`schemas/common/enums.yaml` 必须覆盖：

1. `SecurityLevel`: `PUBLIC`、`INTERNAL`、`CONFIDENTIAL`、`SECRET`。
2. `ProcessingTaskStatus`: `PENDING`、`QUEUED`、`RUNNING`、`ORPHANED`、`PARTIAL_SUCCEEDED`、`SUCCEEDED`、`FAILED`、`CANCEL_PENDING`、`CANCELLED`。
3. `ProcessingStep`: `AUDIO_UPLOAD`、`AUDIO_PREPROCESS`、`ASR`、`ALIGNMENT`、`DIARIZATION`、`SPEAKER_EMBEDDING`、`SPEAKER_MATCHING`、`TRANSCRIPT_MERGE`、`SUMMARY`、`EXTRACTION`、`RAG_INDEXING`、`EXPORT`。
4. `StaleStatus`: `ACTIVE`、`STALE`、`REBUILD_QUEUED`、`REBUILDING`、`VALIDATING`、`FAILED`、`DELETED`。
5. `KnowledgeChunkStatus`: `ACTIVE`、`DELETED`。
6. `ExportFormat`: `MARKDOWN`、`DOCX`、`PDF`。
7. `TimestampPrecision`: `WORD`、`SEGMENT`、`APPROXIMATE`。
8. `CitationType`: `MEETING_SEGMENT`、`DOCUMENT_CHUNK`。

## 6. 错误码

`schemas/common/error-codes.yaml` 必须覆盖一期稳定错误码，包含：

1. code。
2. 所属步骤。
3. 默认可重试。
4. 面向用户的默认提示。
5. 面向运维的排查标签。

错误码不得随意改名。需要废弃时新增 replacement，并保留兼容期。

## 7. Public API OpenAPI

`openapi/public-api.yaml` 至少覆盖：

1. auth、users。
2. meetings。
3. audio uploads。
4. processing tasks 和 SSE。
5. transcript。
6. speaker profiles 和 speaker confirmation。
7. minutes、action items、decisions、risks。
8. documents。
9. rag query 和 reindex。
10. exports。
11. legal holds、deletion jobs、break-glass。

契约要求：

1. 所有响应使用统一信封。
2. 所有接口声明鉴权需求。
3. 所有写接口声明 `X-Request-Id` 和 `X-Trace-Id`。
4. 分页接口统一 cursor 模型。
5. 上传接口明确 multipart upload session、part、complete、abort 数据结构。
6. RAG citation schema 必须可区分会议和文档引用。

## 8. Internal Callback OpenAPI

`openapi/internal-callback-api.yaml` 覆盖：

```http
PATCH /internal/processing-tasks/{taskId}/steps/{stepName}
POST  /internal/processing-tasks/{taskId}/artifacts
POST  /internal/processing-tasks/{taskId}/transcript
POST  /internal/processing-tasks/{taskId}/speaker-candidates
POST  /internal/processing-tasks/{taskId}/complete
POST  /internal/processing-tasks/{taskId}/fail
```

所有 callback endpoint 必须声明请求头：

```http
X-Worker-Id
X-Attempt-No
X-Lease-Owner
X-Request-Id
X-Trace-Id
X-Timestamp
X-Nonce
Idempotency-Key
X-Signature
```

schema 必须包含 `tenantId`、`taskId`、`artifactManifestId` 或足以生成 manifest 的字段。

## 9. RabbitMQ 消息 Schema

`processing-task-message.schema.json` 必须定义：

1. `taskId`。
2. `taskType`。
3. `tenantId`。
4. `meetingId`。
5. `audioFileId`。
6. `audioUri`。
7. `securityLevel`。
8. `attemptNo`。
9. `expectedInputVersion`。
10. `language`。
11. `channelMap`。
12. `knownParticipants`。
13. `minSpeakers`、`maxSpeakers`。
14. `options`。
15. `traceId`。

Schema 必须开启 required 校验，禁止关键字段缺失后由 worker 猜测。

## 10. 版本策略

1. 契约版本采用语义化版本。
2. 兼容新增字段必须为 optional 或提供默认值。
3. 删除字段、改字段含义、改枚举名属于 breaking change。
4. breaking change 需要同时修改 `meeting-web`、`meeting-api` 和 `ai-worker`。
5. callback payload 变更必须保持旧 worker 重试期间可被 Java 识别。

## 11. 验收标准

1. OpenAPI 能通过 lint。
2. JSON Schema 能校验示例消息。
3. 枚举与 Java、TypeScript、Python 使用的枚举一致。
4. 错误码与前端提示、后端异常、worker 错误上报一致。
5. Public API、callback API、RabbitMQ schema 与 `docs/app-api-contracts.md` 无语义冲突。
