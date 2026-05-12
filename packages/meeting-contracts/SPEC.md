# meeting-contracts Spec

## 1. 工程定位

`meeting-contracts` 是跨工程契约的单一事实来源。它定义 `meeting-web`、`meeting-api`、`ai-worker`、RabbitMQ 消息和通用枚举错误码之间共享的数据格式。

职责：

1. 保存 Public API 的 OpenAPI 契约。
2. 保存 Internal Callback API 的 OpenAPI 契约。
3. 保存 RabbitMQ 消息 JSON Schema。
4. 保存通用枚举和稳定错误码。
5. 为后续 TypeScript、Java、Python SDK 生成提供输入。

## 1.1 开发准入

`meeting-contracts` 是各端并行开发前的硬门槛：

1. MVP-0 必须能 lint `openapi/public-api.yaml`、`openapi/internal-callback-api.yaml`，并用 JSON Schema 校验 RabbitMQ task message。
2. 枚举和错误码新增时必须先改 `schemas/common/*.yaml`，再同步 Java / TypeScript / Python 手写或生成类型。
3. SDK codegen 可以在一期早期手写替代，但手写类型必须在对应工程中标注来源并接受契约一致性检查。
4. `fixtures/**` 仍是后续增强；新增后才作为 CI 必过项，未新增前不能阻塞 MVP-0 开发。

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
scripts/
```

文件职责：

| 文件 / 目录 | 事实类型 | 主要消费者 |
|---|---|---|
| `openapi/public-api.yaml` | Web 到 API 的 endpoint、schema、SSE 和错误响应 | `meeting-web`、`meeting-api-adapter`、MSW / Playwright |
| `openapi/internal-callback-api.yaml` | Worker callback endpoint、header、签名字段、body schema | `ai-worker`、`meeting-api-adapter`、callback 回放测试 |
| `schemas/rabbitmq/processing-task-message.schema.json` | API 投递给 worker 的任务消息 | `meeting-api-app`、`ai-worker`、RabbitMQ contract test |
| `schemas/common/enums.yaml` | 跨工程枚举 | Java / TypeScript / Python codegen |
| `schemas/common/error-codes.yaml` | 稳定错误码、retryable、i18n key、运维标签 | 前端 error mapper、Java exception mapper、worker fail callback |
| `scripts/` | 契约生成、lint、枚举一致性和 CI 辅助脚本 | CI、pre-commit、各端 codegen |

`fixtures/**` 可以作为后续契约回放样本目录引入，但一期仓库当前没有该目录时不得把 fixture replay 作为必过验收项。新增该目录后，每个 valid / invalid 样本必须纳入 CI 校验。

## 3. JSON 约定

1. JSON 字段使用 `camelCase`。
2. ID 字段使用字符串，例如 `tenantId`、`meetingId`、`taskId`。
3. 时间使用 ISO-8601 UTC 字符串。
4. 音频时间戳使用毫秒整型字段，例如 `startMs`、`endMs`。
5. 枚举值使用大写下划线。
6. 置信度范围为 `0.0` 到 `1.0`。
7. 大文本、大数组和模型原始输出优先写 TOS，契约中返回 `artifactUri`、`sha256` 和 summary。
8. `textRedactionBeforeThirdPartyLlm` 是 LLM 调用审计契约固定字段，语义为发送至第三方 LLM 前是否做过文本脱敏；一期值恒为 `false`，契约消费方不得把它当作可自行切换的脱敏开关。
9. RAG answer 必须携带 `coverage` 字段，取值来自 `RagAnswerCoverage`，并作为前端和服务端缓存 key 的一部分。

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

1. `MeetingStatus`: `CREATED`、`PROCESSING`、`SUCCEEDED`、`FAILED`、`DELETED`。
2. `SecurityLevel`: `PUBLIC`、`INTERNAL`、`CONFIDENTIAL`、`SECRET`。
3. `ProcessingTaskStatus`: `PENDING`、`QUEUED`、`RUNNING`、`ORPHANED`、`PARTIAL_SUCCEEDED`、`SUCCEEDED`、`FAILED`、`CANCEL_PENDING`、`CANCELLED`。
4. `ProcessingTaskPhase`: `WORKER_DAG_RUNNING`、`WORKER_DAG_DONE`、`JAVA_LLM_RUNNING`、`TERMINAL`。
5. `StepStatus`: `PENDING`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`SKIPPED`、`CANCELLED`。
6. `ProcessingStep`: `AUDIO_UPLOAD`、`AUDIO_PREPROCESS`、`ASR`、`ALIGNMENT`、`DIARIZATION`、`SPEAKER_EMBEDDING`、`SPEAKER_MATCHING`、`TRANSCRIPT_MERGE`、`SUMMARY`、`EXTRACTION`、`RAG_INDEXING`、`EXPORT`。
7. `StaleStatus`: `ACTIVE`、`STALE`、`REBUILD_QUEUED`、`REBUILDING`、`VALIDATING`、`FAILED`、`DELETED`。
8. `KnowledgeChunkStatus`: `ACTIVE`、`DELETED`。
9. `ExportFormat`: `MARKDOWN`、`DOCX`、`PDF`。
10. `TimestampPrecision`: `WORD`、`SEGMENT`、`APPROXIMATE`。
11. `CitationType`: `MEETING_SEGMENT`、`DOCUMENT_CHUNK`。
12. `RagAnswerCoverage`: `TRANSCRIPT_ONLY`、`FULL`。
13. `ProcessingStepUpdateSource`: `JAVA_TASK_SERVICE`、`AI_WORKER_CALLBACK`。
14. `TaskEventType`: `TASK_SNAPSHOT`、`TASK_STARTED`、`TASK_STEP_UPDATED`、`TASK_HEARTBEAT`、`TRANSCRIPT_READY`、`TASK_FAILED`、`TASK_COMPLETED`、`TASK_CANCELLED`。

## 6. 错误码

`schemas/common/error-codes.yaml` 必须覆盖一期稳定错误码，包含：

1. code。
2. 所属步骤。
3. 默认可重试。
4. 面向用户的默认提示。
5. 面向运维的排查标签。
6. i18n key，格式为 `errors.<code>`，一期至少提供 `zh-CN` 默认文案。

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
7. RAG answer DTO 必须包含必填 `coverage: RagAnswerCoverage`，RAG answer cache key 必须纳入该字段。

## 8. Internal Callback OpenAPI

`openapi/internal-callback-api.yaml` 覆盖：

```http
PATCH /internal/processing-tasks/{taskId}/steps/{stepName}
POST  /internal/processing-tasks/{taskId}/artifacts
POST  /internal/processing-tasks/{taskId}/transcript
POST  /internal/processing-tasks/{taskId}/speaker-candidates
POST  /internal/processing-tasks/{taskId}/embeddings
POST  /internal/processing-tasks/{taskId}/complete
POST  /internal/processing-tasks/{taskId}/fail
```

`POST /internal/processing-tasks/{taskId}/complete` 使用 `CompleteWorkerPhaseRequest`，只表示 `phase=WORKER_DAG` 的 worker DAG 阶段完成，不表示整个 processing task 进入 `SUCCEEDED`。Java app 层在收到该 callback 后将 `ProcessingTask.phase` 从 `WORKER_DAG_RUNNING` 推进到 `WORKER_DAG_DONE`，再异步推进 `SUMMARY` / `EXTRACTION`；只有 Java 内部 step 全部满足终态规则后，task 才能进入 `SUCCEEDED` 或 `PARTIAL_SUCCEEDED`，此时 `phase=TERMINAL`。

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

schema 必须包含 `tenantId`、`taskId`，并包含 `artifactManifestId` 或足以生成 manifest 的字段。`meetingId` 按 task 类型条件校验：`MEETING_FULL_PIPELINE` 必须非空且匹配；`TEXT_EMBEDDING` / `RAG_REINDEX` 中 `meetingId` 与 `documentId` 至少一个非空；`SPEAKER_ENROLLMENT` 允许 `meetingId=null`。Internal callback OpenAPI schema 不得把所有 callback 的 `meetingId` 统一设为 required。

`PATCH /internal/processing-tasks/{taskId}/steps/{stepName}` 的幂等例外：

1. `status=RUNNING` 且 `progress > 0` 时视为 heartbeat / progress update，不写入 `callback_events` 幂等表。
2. heartbeat 仍必须携带 `Idempotency-Key`，但 Java 只校验 attempt + lease，按 latest-wins 更新 `heartbeat_at`、`progress`、`lease_expires_at`。
3. 首次 `RUNNING (progress=0)`、`SUCCEEDED`、`FAILED` 仍按普通 callback 写入幂等表并校验 body hash。

HMAC 签名路径：

1. `internal-callback-api.yaml` 的 `servers.url=/internal` 是签名路径的一部分。
2. codegen client 拼接请求 URL 时必须使用 `{server.url}/{path}`，例如 `/internal/processing-tasks/{taskId}/steps/{stepName}`。
3. `signing_string` 中的 `URL_PATH_WITH_QUERY` 必须使用含 `/internal` 前缀的原始 path 和 query；不能只使用 OpenAPI `paths` 里的相对路径。
4. Java 服务端验签必须使用收到请求的原始 URI path，避免 Spring servlet path 归一化后丢掉 `/internal` 前缀。

声纹 embedding 契约：

1. `speaker-candidates` callback 必须始终携带 speaker embedding 明文向量，`PlainSpeakerEmbedding.values` 必须存在且非空。
2. 明文向量只允许通过 internal TLS + HMAC callback 传输，不允许写入 TOS 明文 artifact。
3. `meeting-api` 接收后负责 KMS 信封加密并落库。

文本 embedding 契约：

1. `embeddings` callback 支持直接向量数组或 TOS `artifactUri`。
2. 大批量文本 embedding 优先使用 TOS artifact，callback 传 `artifactUri`、`sha256`、模型版本和 chunk 版本。
3. Java 仍是 chunk 状态和权限事实来源。

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
16. `pipelineSteps`。

Schema 必须开启 required 校验，禁止关键字段缺失后由 worker 猜测。

`pipelineSteps` 表达本条消息要求消费方推进的 worker step 集合，不得包含 Java 在 task 创建前完成的 `AUDIO_UPLOAD`，也不得包含 Java `TaskStepProgressService` 所有的 `SUMMARY` / `EXTRACTION`。schema 必须通过 enum 或 lint 禁止这三个值进入任务消息；worker 如果收到未知 step 或被禁止的 `AUDIO_UPLOAD` / `SUMMARY` / `EXTRACTION`，必须 fail fast 并上报 `INVALID_TASK_MESSAGE`。

`expectedInputVersion.chunkStrategyVersion` 的默认值由 Java `meeting-api` 的 `meeting.chunk.strategy-version` 配置项提供。首次创建 `MEETING_FULL_PIPELINE` 任务时由 Java task 模块写入消息；重建 / 重索引任务使用当前 `knowledge_chunks.chunk_strategy_version` 或更高层配置指定的版本。

## 10. 版本策略

1. 契约版本采用语义化版本。
2. 兼容新增字段必须为 optional 或提供默认值。
3. 删除字段、改字段含义、改枚举名属于 breaking change。
4. breaking change 需要同时修改 `meeting-web`、`meeting-api` 和 `ai-worker`。
5. callback payload 变更必须保持旧 worker 重试期间可被 Java 识别。
6. 契约测试工具一期采用 OpenAPI / JSON Schema lint 为必选项；`fixtures/**` 引入后再启用 fixture replay。暂不引入 Pact，除非后续需要 provider verification workflow。

## 11. 验收标准

1. OpenAPI 能通过 lint。
2. JSON Schema 能校验示例消息。
3. 枚举与 Java、TypeScript、Python 使用的枚举一致。
4. 错误码与前端提示、后端异常、worker 错误上报一致。
5. Public API、callback API、RabbitMQ schema 与 `docs/app-api-contracts.md` 无语义冲突。
6. 如果引入 `fixtures/**`，每个 valid 样本必须通过对应 schema 校验；invalid 样本必须失败且错误路径稳定。
7. `RagAnswerDTO` / RAG answer response 的 `coverage` 为必填字段，且与 `RagAnswerCoverage` 枚举一致。

## 12. Code Generation

生成命令应固化到 `package.json` scripts 或仓库根 `Makefile`，下列命令是一期目标形态：

```bash
openapi-typescript openapi/public-api.yaml -o ../../apps/meeting-web/src/shared/api/types.gen.ts
datamodel-codegen --input openapi/internal-callback-api.yaml --input-file-type openapi --output ../../apps/ai-worker/ai_worker/generated/internal_callback_types.py
openapi-generator generate -g spring -i openapi/public-api.yaml -o ../../apps/meeting-api/meeting-api-client/generated/public-api
datamodel-codegen --input schemas/rabbitmq/processing-task-message.schema.json --output ../../apps/ai-worker/ai_worker/generated/processing_task_message.py
```

CI 要求：

1. 运行 codegen 后 `git diff` 必须为空，或明确提交生成物。
2. Java / TypeScript / Python 枚举必须与 `schemas/common/enums.yaml` 一致。
3. OpenAPI response 必须统一使用 `ApiResponse` envelope。
4. 所有写操作必须声明 `X-Request-Id`、`X-Trace-Id` 和 `Idempotency-Key`，登录除外。
5. `RagAnswerCoverage`、`ProcessingStepUpdateSource`、`ProcessingTaskStatus`、`ProcessingTaskPhase` 必须与 OpenAPI 和各端枚举生成物一致。
6. `processing-task-message.schema.json` 的 `pipelineSteps` 不得允许 `AUDIO_UPLOAD` / `SUMMARY` / `EXTRACTION`。

## 13. Spectral Lint

仓库根或本包内必须提供 `.spectral.yaml`，至少包含：

1. path 必须以 `/` 开头并挂载到 `/api` 或 `/internal` server。
2. operationId 必须唯一且使用 lowerCamelCase。
3. Public API 写操作必须声明 `X-Request-Id`、`X-Trace-Id`。
4. 非登录写操作必须声明 `Idempotency-Key`。
5. callback API 必须声明 `X-Worker-Id`、`X-Attempt-No`、`X-Lease-Owner`、`X-Timestamp`、`X-Nonce`、`X-Signature`。
6. callback API 必须声明 `servers.url=/internal`，并通过 lint 防止 internal callback codegen 丢失 server prefix；该规则以 `info.title` 中的 internal callback 语义识别文件，不依赖某个具体 path 是否存在。
7. 4xx / 5xx response 必须引用统一错误响应 schema。
