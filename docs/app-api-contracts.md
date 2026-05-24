# 应用间 API 契约约定

本文基于 `docs/spec.md` 梳理，用于约定一期各应用之间的 API、消息、回调和 JSON 数据格式。后续落地时，`packages/meeting-contracts` 作为跨工程契约单一事实来源：

```text
packages/meeting-contracts/openapi/public-api.yaml
packages/meeting-contracts/openapi/internal-callback-api.yaml
packages/meeting-contracts/openapi/ai-worker-internal-api.yaml
packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json
packages/meeting-contracts/schemas/common/enums.yaml
packages/meeting-contracts/schemas/common/error-codes.yaml
```

## 1. 应用边界

| 调用方 | 被调用方 | 协议 | 用途 |
|---|---|---|---|
| `meeting-web` | `meeting-api` | HTTPS JSON / SSE | 登录、会议、上传、任务进度、转录、纪要、RAG、导出、管理 |
| `meeting-api` | RabbitMQ | JSON message | 投递音频处理、重建、导出等异步任务 |
| `meeting-api` | `ai-worker` | Internal HTTPS JSON + HMAC | RAG query-time rerank，仅发送已授权候选 chunk |
| `ai-worker` | `meeting-api` | Internal HTTPS JSON + HMAC / callback | 回写任务步骤、产物、转录、声纹候选和 worker phase 完成状态；在 `SPEAKER_MATCHING` 前同步拉取当前 active 声纹参考向量 |
| `ai-worker` | TOS | TOS URI / SDK | 读取音频，写入中间 JSON、模型产物、导出中间件 |
| `meeting-api` | TOS | TOS URI / SDK | 上传签名、文件元信息、导出文件、下载签名 |
| `meeting-api` | DashScope | OpenAI-compatible API | 纪要、待办、决策、风险、RAG 答案生成 |
| 运维 / 调试工具 | `ai-worker` | Internal HTTPS JSON | 健康检查、模型状态、workflow 调试 |

约束：

1. `meeting-api` 是主产品入口、业务事实来源、权限来源和租户上下文来源。
2. `ai-worker` 不直接写 PostgreSQL 业务库，只能通过 internal callback API 回写结果。
3. `meeting-web` 不直接访问 Python、RabbitMQ、数据库或 DashScope。
4. DashScope 调用统一经 `meeting-api` 的 `llm-gateway` 审计；音频、声纹参考音频和声纹 embedding 不得发送给 DashScope。
5. TOS 中的大 JSON 中间产物通过 `oss://...` URI 引用，业务库只保存文件元信息、hash、版本和摘要 metadata。

## 2. 通用 JSON 约定

事实来源：通用响应 envelope、公共 header、枚举和错误码以 `packages/meeting-contracts/openapi/*.yaml` 与 `packages/meeting-contracts/schemas/common/*.yaml` 为准。本节示例用于说明跨应用消费约定，不作为可独立修改的第二份 schema。

### 2.1 字段风格

1. JSON 字段统一使用 `camelCase`。
2. ID 字段统一使用字符串，例如 `tenantId`、`meetingId`、`taskId`。
3. 时间字段统一使用 ISO-8601 UTC 字符串，例如 `2026-05-11T06:30:00Z`。
4. 音频时间戳统一使用毫秒整型字段，例如 `startMs`、`endMs`。
5. 枚举值统一使用大写下划线，例如 `INTERNAL`、`RUNNING`、`STALE`。
6. 金额、分数、置信度等数值不得用字符串表达；置信度范围为 `0.0` 到 `1.0`。
7. 大文本、大数组、大模型输出优先写 TOS，API 中返回 `artifactUri`、`sha256` 和 summary。
8. `textRedactionBeforeThirdPartyLlm` 是 LLM 调用审计契约固定字段，语义为发送至第三方 LLM 前是否做过文本脱敏；一期值恒为 `false`，契约消费方不得把它当作可自行切换的脱敏开关。

### 2.2 公共请求头

`meeting-web -> meeting-api` 请求必须携带：

```http
Authorization: Bearer <access_token>
X-Request-Id: req_20260511_000001
X-Trace-Id: trace_20260511_000001
Content-Type: application/json
Accept: application/json
```

租户上下文由登录态解析；如未来支持多租户切换，可增加：

```http
X-Tenant-Id: t_001
```

`ai-worker -> meeting-api` callback 请求必须携带：

```http
X-Worker-Id: worker_gpu_001
X-Attempt-No: 1
X-Lease-Owner: lease_task_001_attempt_1
X-Request-Id: req_20260511_000101
X-Trace-Id: trace_001
X-Timestamp: 2026-05-11T06:30:00Z
X-Nonce: nonce_001
Idempotency-Key: task_001:TRANSCRIPT_MERGE:attempt_1:v1
X-Signature: hmac-sha256=<hex>
Content-Type: application/json
Accept: application/json
```

HMAC 签名规范：

```text
signing_string = X-Timestamp + "\n" +
                 X-Nonce + "\n" +
                 HTTP_METHOD + "\n" +
                 URL_PATH_WITH_QUERY + "\n" +
                 SHA256(request_body).hex
signature      = HMAC-SHA256(secret, signing_string)
X-Signature    = "hmac-sha256=" + hex(signature)
```

`URL_PATH_WITH_QUERY` 包含 `/internal` 前缀和 query string，不包含 scheme、host、fragment。`request_body` 取实际发送的 UTF-8 bytes，空 body 使用 SHA-256 空串值。服务端必须校验 `X-Timestamp` 与当前时间偏差不超过 `5min`，并用 `(tenantId, X-Nonce)` 做短期去重。

### 2.3 响应信封

业务 API 成功响应：

```json
{
  "success": true,
  "data": {
    "meetingId": "m_001"
  },
  "error": null,
  "requestId": "req_20260511_000001",
  "traceId": "trace_20260511_000001"
}
```

业务 API 失败响应：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SECURITY_LEVEL_BLOCKED",
    "message": "一期不支持该安全等级的自动 LLM 处理",
    "retryable": false,
    "details": {
      "securityLevel": "SECRET",
      "blockedCapability": "LLM_SUMMARY"
    }
  },
  "requestId": "req_20260511_000001",
  "traceId": "trace_20260511_000001"
}
```

分页响应：

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "meetingId": "m_001",
        "title": "方案评审会"
      }
    ],
    "page": {
      "cursor": "cursor_next_001",
      "hasMore": true,
      "limit": 20
    }
  },
  "error": null,
  "requestId": "req_20260511_000001",
  "traceId": "trace_20260511_000001"
}
```

## 3. 枚举约定

事实来源：完整枚举清单以 `packages/meeting-contracts/schemas/common/enums.yaml` 为准。下列枚举只列一期主要值，代码生成、前端 dictionary、Java enum 和 Python enum 必须从事实来源校验一致性。

安全等级：

```json
["PUBLIC", "INTERNAL", "CONFIDENTIAL", "SECRET"]
```

任务状态：

```json
[
  "PENDING",
  "QUEUED",
  "RUNNING",
  "ORPHANED",
  "PARTIAL_SUCCEEDED",
  "SUCCEEDED",
  "FAILED",
  "CANCEL_PENDING",
  "CANCELLED"
]
```

处理步骤：

```json
[
  "AUDIO_UPLOAD",
  "AUDIO_PREPROCESS",
  "ASR",
  "ALIGNMENT",
  "DIARIZATION",
  "SPEAKER_EMBEDDING",
  "SPEAKER_MATCHING",
  "TRANSCRIPT_MERGE",
  "SUMMARY",
  "EXTRACTION",
  "RAG_INDEXING",
  "EXPORT"
]
```

内容新鲜度状态：

```json
[
  "ACTIVE",
  "STALE",
  "REBUILD_QUEUED",
  "REBUILDING",
  "VALIDATING",
  "FAILED",
  "DELETED"
]
```

## 4. `meeting-web -> meeting-api` Public API

Public API 路由前缀为 `/api`。所有接口必须经过登录态鉴权、租户上下文设置、权限校验、审计和限流。

事实来源：endpoint、operationId、请求体、响应体、SSE 事件 schema 和错误响应以 `packages/meeting-contracts/openapi/public-api.yaml` 为准。本节样例用于联调和 fixture 编写，字段增删必须先改 OpenAPI。

### 4.1 登录

```http
POST /api/auth/login
```

请求：

```json
{
  "username": "alice@example.com",
  "password": "example-password"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "accessToken": "jwt_access_token",
    "refreshToken": "jwt_refresh_token",
    "expiresAt": "2026-05-11T08:30:00Z",
    "user": {
      "userId": "u_001",
      "tenantId": "t_001",
      "displayName": "Alice",
      "roles": ["TENANT_ADMIN"]
    }
  },
  "error": null,
  "requestId": "req_001",
  "traceId": "trace_001"
}
```

### 4.2 当前用户

```http
GET /api/auth/me
```

响应：

```json
{
  "success": true,
  "data": {
    "userId": "u_001",
    "tenantId": "t_001",
    "personId": "person_001",
    "displayName": "Alice",
    "roles": ["TENANT_ADMIN"],
    "permissions": [
      "meeting:create",
      "meeting:read",
      "speaker:manage"
    ]
  },
  "error": null,
  "requestId": "req_002",
  "traceId": "trace_001"
}
```

### 4.3 创建会议

```http
POST /api/meetings
```

请求：

```json
{
  "title": "方案评审会",
  "scheduledStartAt": "2026-05-11T07:00:00Z",
  "securityLevel": "INTERNAL",
  "language": "zh",
  "participants": [
    {
      "personId": "person_001",
      "displayName": "Alice",
      "role": "HOST"
    },
    {
      "personId": "person_002",
      "displayName": "Bob",
      "role": "PARTICIPANT"
    }
  ]
}
```

响应：

```json
{
  "success": true,
  "data": {
    "meetingId": "m_001",
    "tenantId": "t_001",
    "title": "方案评审会",
    "securityLevel": "INTERNAL",
    "status": "CREATED",
    "transcriptVersion": 0,
    "minutesVersion": 0,
    "createdAt": "2026-05-11T06:30:00Z"
  },
  "error": null,
  "requestId": "req_003",
  "traceId": "trace_001"
}
```

### 4.4 初始化音频上传

```http
POST /api/meetings/{meetingId}/files/audio/uploads
```

请求：

```json
{
  "fileName": "review-meeting.wav",
  "contentType": "audio/wav",
  "sizeBytes": 734003200,
  "sha256": "b1946ac92492d2347c6235b4d2611184",
  "durationMs": 7200000
}
```

响应：

```json
{
  "success": true,
  "data": {
    "uploadId": "upload_001",
    "fileId": "file_001",
    "bucket": "meeting-audio",
    "objectKey": "tenant/t_001/meeting/m_001/raw/file_001.wav",
    "partSizeBytes": 8388608,
    "expiresAt": "2026-05-11T07:30:00Z"
  },
  "error": null,
  "requestId": "req_004",
  "traceId": "trace_001"
}
```

上传分片：

```http
POST /api/meetings/{meetingId}/files/audio/uploads/{uploadId}/parts
```

请求：

```json
{
  "partNumber": 1,
  "sizeBytes": 8388608,
  "sha256": "part_hash_001"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "partNumber": 1,
    "uploadUrl": "https://tos.example.com/signed-upload-url",
    "expiresAt": "2026-05-11T06:45:00Z"
  },
  "error": null,
  "requestId": "req_005",
  "traceId": "trace_001"
}
```

完成上传：

```http
POST /api/meetings/{meetingId}/files/audio/uploads/{uploadId}/complete
```

请求：

```json
{
  "parts": [
    {
      "partNumber": 1,
      "etag": "etag_part_001",
      "sha256": "part_hash_001"
    }
  ],
  "fileSha256": "b1946ac92492d2347c6235b4d2611184"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "fileId": "file_001",
    "audioUri": "oss://meeting-audio-auska/tenant/t_001/meeting/m_001/raw/file_001.wav",
    "status": "UPLOADED"
  },
  "error": null,
  "requestId": "req_006",
  "traceId": "trace_001"
}
```

### 4.5 创建处理任务

```http
POST /api/meetings/{meetingId}/processing-tasks
```

请求：

```json
{
  "taskType": "MEETING_FULL_PIPELINE",
  "audioFileId": "file_001",
  "options": {
    "enableAsr": true,
    "enableDiarization": true,
    "enableSpeakerRecognition": true,
    "enableRagIndexing": true
  },
  "holdAtWorkerPhase": true
}
```

`holdAtWorkerPhase=true` 用于工作站人工确认链路：worker 完成 `WORKER_DAG` 后 Java 停在 `WORKER_DAG_DONE`，等待用户确认转录 / speaker，再调用 resume 接口进入 `SUMMARY` / `EXTRACTION`。默认值为 `false`，普通后台任务由 Java listener 自动推进。

响应：

```json
{
  "success": true,
  "data": {
    "taskId": "task_001",
    "meetingId": "m_001",
    "status": "QUEUED",
    "attemptNo": 1,
    "traceId": "trace_001",
    "estimatedWaitSeconds": 120,
    "steps": [
      {
        "stepName": "AUDIO_PREPROCESS",
        "status": "PENDING",
        "progress": 0
      },
      {
        "stepName": "ASR",
        "status": "PENDING",
        "progress": 0
      }
    ]
  },
  "error": null,
  "requestId": "req_007",
  "traceId": "trace_001"
}
```

### 4.6 查询任务

```http
GET /api/processing-tasks/{taskId}
```

响应：

```json
{
  "success": true,
  "data": {
    "taskId": "task_001",
    "meetingId": "m_001",
    "status": "RUNNING",
    "attemptNo": 1,
    "currentStep": "ASR",
    "lastErrorCode": null,
    "retryable": true,
    "steps": [
      {
        "stepName": "AUDIO_PREPROCESS",
        "status": "SUCCEEDED",
        "progress": 100,
        "startedAt": "2026-05-11T06:33:00Z",
        "finishedAt": "2026-05-11T06:34:00Z"
      },
      {
        "stepName": "ASR",
        "status": "RUNNING",
        "progress": 42,
        "startedAt": "2026-05-11T06:34:00Z",
        "heartbeatAt": "2026-05-11T06:36:00Z"
      }
    ]
  },
  "error": null,
  "requestId": "req_008",
  "traceId": "trace_001"
}
```

恢复 Java LLM phase：

```http
POST /api/processing-tasks/{taskId}:resume-java-phase
```

语义：

1. 仅接受 `WORKER_DAG_DONE` 或幂等的 `JAVA_LLM_RUNNING` / `TERMINAL`。
2. 当前实现会同步运行 Java-owned `SUMMARY` 和 `EXTRACTION`，成功后 task 进入 `TERMINAL/SUCCEEDED`；失败时写入对应 step error code 并以 `PARTIAL_SUCCEEDED` 或 `FAILED` 收敛。
3. `SUMMARY` 成功会发布 `MinutesGeneratedEvent`，由 RAG indexer 重建 `sourceType=MINUTES` 的 chunks。

响应同 `ProcessingTaskResponse`。

### 4.7 任务事件 SSE

```http
GET /api/processing-tasks/{taskId}/events
```

事件数据：

```json
{
  "eventId": "task_001:00000042",
  "sequenceNo": 42,
  "eventType": "TASK_STEP_UPDATED",
  "taskId": "task_001",
  "meetingId": "m_001",
  "stepName": "ASR",
  "status": "RUNNING",
  "progress": 42,
  "retryable": true,
  "errorCode": null,
  "emittedAt": "2026-05-11T06:36:00Z"
}
```

断线恢复：

1. 服务端 SSE event id 使用 `eventId`。
2. `sequenceNo` 在同一 `taskId` 内单调递增。
3. 客户端重连时携带 `Last-Event-Id: task_001:00000042`。
4. 服务端从该 event 之后续发；如果事件已过保留窗口，则先发送当前 task 快照，再继续推送新事件。

事件清单：

| eventType | 必填字段 | 触发时机 |
|---|---|---|
| `TASK_SNAPSHOT` | `eventId`、`sequenceNo`、`taskId`、`status`、`phase`、`steps`、`emittedAt` | SSE 建连、无法续接历史窗口、客户端显式刷新 |
| `TASK_STARTED` | `eventId`、`sequenceNo`、`taskId`、`status=RUNNING`、`attemptNo`、`emittedAt` | worker 成功 claim lease |
| `TASK_STEP_UPDATED` | `eventId`、`sequenceNo`、`taskId`、`stepName`、`status`、`progress`、`emittedAt` | step 开始、进度、完成或错误码变化 |
| `TASK_HEARTBEAT` | `eventId`、`sequenceNo`、`taskId`、`stepName`、`status=RUNNING`、`leaseExpiresAt`、`emittedAt` | worker heartbeat 被接受 |
| `TRANSCRIPT_READY` | `eventId`、`sequenceNo`、`taskId`、`meetingId`、`transcriptVersion`、`artifactManifestId`、`emittedAt` | 结构化转录落库成功 |
| `TASK_COMPLETED` | `eventId`、`sequenceNo`、`taskId`、`status`、`phase=TERMINAL`、`completedSteps`、`artifactManifestId`、`emittedAt` | Java 完成 worker phase 后的 `SUMMARY` / `EXTRACTION` 与所有必做 step，并将 task 推进到 `SUCCEEDED` 或 `PARTIAL_SUCCEEDED` |
| `TASK_FAILED` | `eventId`、`sequenceNo`、`taskId`、`status=FAILED`、`errorCode`、`retryable`、`emittedAt` | task 失败或重试耗尽 |
| `TASK_CANCELLED` | `eventId`、`sequenceNo`、`taskId`、`status=CANCELLED`、`emittedAt` | 取消完成 |

`eventId` 编码为 `{taskId}:{sequenceNo}`，`sequenceNo` 使用 8 位左补零十进制字符串；同一 `taskId` 内不得复用。SSE 数据保留窗口默认 `30min`，超出窗口后必须先发送 `TASK_SNAPSHOT`。

### 4.8 获取转录

```http
GET /api/meetings/{meetingId}/transcript
```

响应：

```json
{
  "success": true,
  "data": {
    "meetingId": "m_001",
    "transcriptVersion": 1,
    "staleStatus": "ACTIVE",
    "segments": [
      {
        "segmentId": "seg_001",
        "startMs": 13000,
        "endMs": 28000,
        "speakerLabel": "SPEAKER_01",
        "speakerDisplayName": "李四",
        "originalText": "预算这块目前还有二十万缺口。",
        "editedText": null,
        "currentText": "预算这块目前还有二十万缺口。",
        "asrConfidence": 0.91,
        "diarizationConfidence": 0.84,
        "timestampPrecision": "SEGMENT"
      }
    ]
  },
  "error": null,
  "requestId": "req_009",
  "traceId": "trace_001"
}
```

编辑片段：

```http
PATCH /api/meetings/{meetingId}/transcript/segments/{segmentId}
```

请求：

```json
{
  "expectedTranscriptVersion": 1,
  "editedText": "预算这块目前还有二十万缺口，如果要按六月底上线，需要这周确认供应商。",
  "editReason": "人工校对"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "segmentId": "seg_001",
    "transcriptVersion": 2,
    "downstreamStaleStatus": "STALE",
    "affectedObjects": {
      "minutes": true,
      "actionItems": true,
      "decisions": true,
      "risks": true,
      "ragChunks": true
    }
  },
  "error": null,
  "requestId": "req_010",
  "traceId": "trace_001"
}
```

### 4.8.1 会议工作站上下文

会议级参考文档：

```http
GET    /api/meetings/{meetingId}/documents
POST   /api/meetings/{meetingId}/documents
DELETE /api/meetings/{meetingId}/documents/{documentId}
```

`POST` 请求：

```json
{
  "documentId": "doc_001",
  "role": "REFERENCE"
}
```

Java 使用 `meeting_documents` 软删除关联表保存引用关系；同一会议未删除的 `(meetingId, documentId)` 唯一。创建处理任务时，`REFERENCE` 文档 ID 会进入 task payload 的 `referenceDocumentIds`，纪要生成时再读取文档 chunks 并按约 2k 字符预算放入 LLM context。

会议级术语：

```http
GET   /api/meetings/{meetingId}/glossary
PATCH /api/meetings/{meetingId}/glossary
```

`PATCH` 请求：

```json
{
  "terms": [
    {"term": "CMDB", "aliases": ["配置管理数据库"]},
    {"term": "RTO", "aliases": ["恢复时间目标"]}
  ]
}
```

`terms` 最大 200 条，单个 `term` 不超过 64 字符；落库字段为 `meetings.glossary_terms jsonb`，创建处理任务时同步进入 task payload 的 `glossaryTerms`。

### 4.9 确认 speaker

```http
POST /api/meetings/{meetingId}/speakers/{speakerLabel}/confirm
```

请求：

```json
{
  "personId": "person_002",
  "expectedTranscriptVersion": 2,
  "confidence": 0.92,
  "source": "HUMAN_CONFIRM"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "meetingId": "m_001",
    "speakerLabel": "SPEAKER_01",
    "personId": "person_002",
    "displayName": "李四",
    "status": "CONFIRMED",
    "ragChunksStaleStatus": "STALE"
  },
  "error": null,
  "requestId": "req_011",
  "traceId": "trace_001"
}
```

### 4.10 获取纪要

```http
GET /api/meetings/{meetingId}/minutes
```

响应：

```json
{
  "success": true,
  "data": {
    "meetingId": "m_001",
    "minutesId": "minutes_001",
    "minutesVersion": 1,
    "staleStatus": "ACTIVE",
    "markdown": "## 核心结论\n\n- 六月底上线目标不变。",
    "sections": [
      {
        "type": "CONCLUSION",
        "title": "核心结论",
        "items": [
          {
            "text": "六月底上线目标不变。",
            "evidence": [
              {
                "segmentId": "seg_001",
                "startMs": 13000,
                "endMs": 28000,
                "evidenceTextSnapshot": "如果要按六月底上线，需要这周确认供应商。"
              }
            ]
          }
        ]
      }
    ],
    "artifactManifestId": "artifact_001"
  },
  "error": null,
  "requestId": "req_012",
  "traceId": "trace_001"
}
```

重生成纪要：

```http
POST /api/meetings/{meetingId}/minutes/regenerate
```

请求：

```json
{
  "expectedTranscriptVersion": 2,
  "regenerateMode": "DIFF_ONLY",
  "reason": "转录人工校对后重新生成"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "taskId": "task_regen_001",
    "meetingId": "m_001",
    "status": "QUEUED",
    "willNotOverwriteConfirmedFacts": true
  },
  "error": null,
  "requestId": "req_013",
  "traceId": "trace_001"
}
```

### 4.11 RAG 查询

```http
POST /api/rag/query
```

请求：

```json
{
  "query": "六月底上线最大的风险是什么？",
  "scope": {
    "meetingIds": ["m_001"],
    "documentIds": ["doc_001"]
  },
  "topK": 8,
  "includeCitations": true
}
```

响应：

```json
{
  "success": true,
  "data": {
    "answer": "最大风险是供应商本周无法确认，导致预算缺口和交付排期同时受影响。",
    "coverage": "FULL",
    "citations": [
      {
        "type": "MEETING_SEGMENT",
        "meetingId": "m_001",
        "meetingTitle": "方案评审会",
        "segmentId": "seg_001",
        "speaker": "李四",
        "startMs": 13000,
        "endMs": 28000,
        "content": "预算这块目前还有二十万缺口，如果要按六月底上线，需要这周确认供应商。"
      },
      {
        "type": "DOCUMENT_CHUNK",
        "documentId": "doc_001",
        "documentTitle": "项目方案.docx",
        "chunkId": "chunk_001",
        "page": 3,
        "content": "供应商确认是六月底上线的关键前置条件。"
      }
    ],
    "artifactManifestId": "artifact_rag_001"
  },
  "error": null,
  "requestId": "req_014",
  "traceId": "trace_001"
}
```

内部实现顺序：`meeting-api` 先计算权限 scope，完成 pgvector / keyword 召回和 PostgreSQL 权限二次校验，再通过 `packages/meeting-contracts/openapi/ai-worker-internal-api.yaml` 定义的 `POST /internal/rerank` 同步调用 `ai-worker` 做 query-time rerank。`ai-worker` 不接收前端请求，也不重新判断业务权限。Rerank 超时、503 或 5xx 可降级为 RRF 排序；400 / 401 表示内部契约或签名配置错误，返回 `RERANK_CONTRACT_ERROR`，不降级。

### 4.12 导出

```http
POST /api/meetings/{meetingId}/exports
```

请求：

```json
{
  "format": "PDF",
  "includeTranscript": true,
  "includeCitations": true,
  "includeActionItems": true,
  "watermark": {
    "enabled": false
  }
}
```

响应：

```json
{
  "success": true,
  "data": {
    "exportId": "export_001",
    "meetingId": "m_001",
    "status": "QUEUED",
    "format": "PDF",
    "expiresAt": "2026-05-18T06:30:00Z"
  },
  "error": null,
  "requestId": "req_015",
  "traceId": "trace_001"
}
```

查询导出：

```http
GET /api/exports/{exportId}
```

响应：

```json
{
  "success": true,
  "data": {
    "exportId": "export_001",
    "status": "SUCCEEDED",
    "format": "PDF",
    "downloadUrl": "https://tos.example.com/signed-download-url",
    "downloadUrlExpiresAt": "2026-05-11T07:30:00Z",
    "sha256": "export_hash_001",
    "revoked": false
  },
  "error": null,
  "requestId": "req_016",
  "traceId": "trace_001"
}
```

`downloadUrl` 是 TOS 预签名 URL，`downloadUrlExpiresAt` 由 `export_jobs.download_expires_at` 派生。撤销短链使用既有：

```http
POST /api/exports/{exportId}/revoke-link
```

撤销后再次查询导出时不再返回可用 `downloadUrl`，`revoked=true`。

### 4.13 高频写操作补充样例

转录依赖重生成：

```json
{
  "endpoint": "POST /api/meetings/{meetingId}/transcript/regenerate",
  "request": {
    "expectedTranscriptVersion": 2,
    "regenerateScope": ["SUMMARY", "ACTION_ITEMS", "RAG_INDEXING"],
    "reason": "人工校对后重建下游产物"
  },
  "responseData": {
    "taskId": "task_regen_001",
    "status": "QUEUED"
  }
}
```

声纹注册与 speaker reject：

```json
{
  "createEnrollment": {
    "endpoint": "POST /api/speaker-profiles/{profileId}/enrollments",
    "request": {
      "referenceAudioFileId": "file_voice_001",
      "consentRecordId": "consent_001",
      "expiresAt": "2027-05-11T00:00:00Z"
    },
    "responseData": {
      "taskId": "task_enroll_001",
      "status": "QUEUED"
    }
  },
  "rejectSpeaker": {
    "endpoint": "POST /api/meetings/{meetingId}/speakers/{speakerLabel}/reject",
    "request": {
      "candidateProfileId": "profile_002",
      "expectedTranscriptVersion": 2,
      "reason": "人工确认不是该人员"
    },
    "responseData": {
      "speakerLabel": "SPEAKER_01",
      "status": "REJECTED",
      "ragChunksStaleStatus": "STALE"
    }
  }
}
```

待办接受 / 拒绝：

```json
{
  "accept": {
    "endpoint": "POST /api/meetings/{meetingId}/action-items/{itemId}/accept",
    "request": {
      "expectedVersion": 1,
      "assigneePersonId": "person_002",
      "dueDate": "2026-05-15"
    },
    "responseData": {
      "itemId": "item_001",
      "status": "ACCEPTED"
    }
  },
  "reject": {
    "endpoint": "POST /api/meetings/{meetingId}/action-items/{itemId}/reject",
    "request": {
      "expectedVersion": 1,
      "reason": "不是实际待办"
    },
    "responseData": {
      "itemId": "item_001",
      "status": "REJECTED"
    }
  }
}
```

文档上传 / 删除、legal hold、deletion job 和 break-glass 的详细字段以 OpenAPI 为准；最小请求均必须包含 `Idempotency-Key`、`X-Request-Id`、`X-Trace-Id`，并返回可追踪的业务 id 或 task id。

## 5. `meeting-api -> ai-worker` RabbitMQ 消息

事实来源：`ai-worker` RabbitMQ payload 以 `packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json` 为准；枚举值以 `packages/meeting-contracts/schemas/common/enums.yaml` 为准。本节只说明 routing 与典型消息形态。`export-queue` 不进入 Python `ai-worker`，其消息体以 `packages/meeting-contracts/schemas/rabbitmq/export-job-message.schema.json` 为准。

RabbitMQ 消息必须是 JSON，消息体必须能通过 `processing-task-message.schema.json` 校验。消息属性必须包含：

```json
{
  "contentType": "application/json",
  "messageId": "msg_task_001_attempt_1",
  "correlationId": "trace_001",
  "deliveryMode": 2,
  "headers": {
    "tenantId": "t_001",
    "taskId": "task_001",
    "traceId": "trace_001",
    "attemptNo": 1
  }
}
```

处理任务消息体：

```json
{
  "taskId": "task_001",
  "taskType": "MEETING_FULL_PIPELINE",
  "tenantId": "t_001",
  "meetingId": "m_001",
  "audioFileId": "file_001",
  "audioUri": "oss://meeting-audio-auska/tenant/t_001/meeting/m_001/raw/file.wav",
  "securityLevel": "INTERNAL",
  "attemptNo": 1,
  "expectedInputVersion": {
    "transcriptVersion": 1,
    "minutesVersion": null,
    "chunkStrategyVersion": "chunk-2026.05.1"
  },
  "language": "zh",
  "channelMap": {
    "channelCount": 2,
    "layout": "stereo",
    "channels": [
      {
        "index": 0,
        "label": "local_room"
      },
      {
        "index": 1,
        "label": "remote_room"
      }
    ]
  },
  "knownParticipants": ["person_001", "person_002"],
  "minSpeakers": 1,
  "maxSpeakers": 12,
  "options": {
    "enableAsr": true,
    "enableDiarization": true,
    "enableSpeakerRecognition": true,
    "enableRagIndexing": true
  },
  "traceId": "trace_001"
}
```

消费约束：

1. `ai-worker` 必须以 `taskId + attemptNo + stepName` 做幂等处理。
2. `tenantId`、`meetingId`、`taskId` 与 callback body 必须一致。
3. `securityLevel` 为 `CONFIDENTIAL` / `SECRET` 时，任何 LLM 相关 step 必须 fail closed。
4. `audioUri` 只允许读取授权 TOS 前缀，不允许任意路径读取。
5. 消费失败可重试；重试耗尽后进入 DLQ，并保留 `taskId`、`tenantId`、`stepName`、`errorCode`、`workerId`、`artifactManifestId`。

`export-queue` 消息示例：

```json
{
  "tenantId": "t_001",
  "meetingId": "m_001",
  "exportId": "exp_001",
  "format": "PDF",
  "expectedInputVersion": {
    "transcriptVersion": 3,
    "minutesVersion": 2,
    "ragVersion": 4
  },
  "traceId": "trace_001"
}
```

## 6. `ai-worker -> meeting-api` Internal Callback API

事实来源：callback endpoint、请求头、签名字段、body schema 和错误响应以 `packages/meeting-contracts/openapi/internal-callback-api.yaml` 为准。本节示例用于 worker / API 联调和回放测试，不允许单独扩展字段。

Internal callback API 路由前缀为 `/internal`，必须使用独立鉴权 filter、独立审计日志和 HMAC-SHA256 签名校验。

### 6.1 回写步骤状态

```http
PATCH /internal/processing-tasks/{taskId}/steps/{stepName}
```

请求：

```json
{
  "tenantId": "t_001",
  "meetingId": "m_001",
  "taskId": "task_001",
  "stepName": "ASR",
  "attemptNo": 1,
  "status": "RUNNING",
  "progress": 42,
  "workerId": "worker_gpu_001",
  "leaseOwner": "lease_task_001_attempt_1",
  "leaseExpiresAt": "2026-05-11T06:40:00Z",
  "heartbeatAt": "2026-05-11T06:36:00Z",
  "startedAt": "2026-05-11T06:34:00Z",
  "error": null
}
```

响应：

```json
{
  "success": true,
  "data": {
    "accepted": true,
    "taskId": "task_001",
    "stepName": "ASR",
    "currentAttemptNo": 1
  },
  "error": null,
  "requestId": "req_cb_001",
  "traceId": "trace_001"
}
```

### 6.2 回写中间产物

```http
POST /internal/processing-tasks/{taskId}/artifacts
```

请求：

```json
{
  "tenantId": "t_001",
  "meetingId": "m_001",
  "taskId": "task_001",
  "stepName": "ASR",
  "attemptNo": 1,
  "artifactType": "RAW_ASR_JSON",
  "artifactUri": "oss://meeting-artifacts/tenant/t_001/meeting/m_001/artifacts/asr/task_001.json",
  "contentType": "application/json",
  "sha256": "artifact_hash_001",
  "sizeBytes": 1048576,
  "metadata": {
    "modelVersion": "qwen3-asr-local-2026.05.1",
    "modelChecksum": "model_hash_001",
    "pipelineVersion": "2026.05.1",
    "inputAudioSha256": "audio_hash_001"
  }
}
```

响应：

```json
{
  "success": true,
  "data": {
    "artifactId": "artifact_file_001",
    "artifactManifestId": "artifact_manifest_001",
    "accepted": true
  },
  "error": null,
  "requestId": "req_cb_002",
  "traceId": "trace_001"
}
```

### 6.3 回写结构化转录

```http
POST /internal/processing-tasks/{taskId}/transcript
```

请求：

```json
{
  "tenantId": "t_001",
  "meetingId": "m_001",
  "taskId": "task_001",
  "attemptNo": 1,
  "transcriptVersion": 1,
  "segments": [
    {
      "segmentId": "seg_001",
      "startMs": 13000,
      "endMs": 28000,
      "speakerLabel": "SPEAKER_01",
      "text": "预算这块目前还有二十万缺口，如果要按六月底上线，需要这周确认供应商。",
      "asrConfidence": 0.91,
      "diarizationConfidence": 0.84,
      "speakerConfidence": 0.87,
      "timestampPrecision": "SEGMENT",
      "source": {
        "asrChunkId": "asr_chunk_001",
        "diarizationTurnId": "turn_001"
      }
    }
  ],
  "metadata": {
    "asrModelVersion": "local-asr-v1",
    "diarizationModelVersion": "local-diar-v1",
    "speakerModelVersion": "local-speaker-v1",
    "termDictionaryVersion": "dict-2026.05.1",
    "channelMap": {
      "channelCount": 2,
      "layout": "stereo"
    },
    "vadVersion": "vad-2026.05.1",
    "pipelineVersion": "2026.05.1"
  },
  "artifactManifestId": "artifact_manifest_001"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "accepted": true,
    "meetingId": "m_001",
    "transcriptVersion": 1,
    "segmentsAccepted": 1
  },
  "error": null,
  "requestId": "req_cb_003",
  "traceId": "trace_001"
}
```

### 6.4 回写 speaker 候选

```http
POST /internal/processing-tasks/{taskId}/speaker-candidates
```

请求：

```json
{
  "tenantId": "t_001",
  "meetingId": "m_001",
  "taskId": "task_001",
  "attemptNo": 1,
  "speakerCandidates": [
    {
      "speakerLabel": "SPEAKER_01",
      "candidates": [
        {
          "personId": "person_002",
          "speakerProfileId": "profile_002",
          "confidence": 0.92,
          "matchStatus": "CANDIDATE"
        }
      ],
      "embedding": {
        "format": "FLOAT32_ARRAY",
        "dimension": 192,
        "values": [0.0123, -0.0456, 0.0789],
        "checksum": "sha256:speaker_embedding_hash_001",
        "modelVersion": "local-speaker-v1",
        "plaintextTransport": "INTERNAL_TLS_HMAC_CALLBACK",
        "persistedBy": "MEETING_API_KMS_ENVELOPE_ENCRYPTION"
      }
    }
  ],
  "artifactManifestId": "artifact_manifest_002"
}
```

声纹 embedding 加密归属：

1. `ai-worker` 通过 internal TLS + HMAC callback 将 speaker embedding 明文回写给 `meeting-api`，不得把明文 embedding 写入 TOS 或普通日志。
2. `meeting-api` 在同一写入用例中调用 KMS 信封加密，数据库只保存密文、wrapped data key、checksum 和模型 metadata。
3. `ai-worker` callback 成功或重试耗尽后必须清除进程内 embedding 明文引用。

响应：

```json
{
  "success": true,
  "data": {
    "accepted": true,
    "candidateCount": 1
  },
  "error": null,
  "requestId": "req_cb_004",
  "traceId": "trace_001"
}
```

### 6.5 回写文本 embedding

```http
POST /internal/processing-tasks/{taskId}/embeddings
```

请求：

```json
{
  "tenantId": "t_001",
  "taskId": "task_001",
  "attemptNo": 1,
  "embeddingBatchId": "emb_batch_001",
  "sourceType": "DOCUMENT",
  "embeddingModelVersion": "bge-m3-local-v1",
  "chunkStrategyVersion": "chunk-2026.05.1",
  "items": [
    {
      "chunkId": "chunk_001",
      "sourceId": "doc_001",
      "sourceVersion": 1,
      "contentHash": "sha256:chunk_hash_001",
      "embedding": {
        "format": "FLOAT32_ARRAY",
        "dimension": 1024,
        "valuesPreview": [0.011, -0.022, 0.033],
        "artifactUri": "oss://meeting-artifacts/tenant/t_001/task/task_001/embedding/emb_batch_001.json",
        "sha256": "sha256:embedding_batch_hash_001"
      }
    }
  ],
  "artifactManifestId": "artifact_manifest_embedding_001"
}
```

文本 embedding 批次可以直接回写 `values`，也可以通过 `artifactUri` 指向 TOS 中的批次 JSON；两者必须至少提供一个。大批次优先使用 `artifactUri`。

响应：

```json
{
  "success": true,
  "data": {
    "accepted": true,
    "embeddingBatchId": "emb_batch_001",
    "itemCount": 1
  },
  "error": null,
  "requestId": "req_cb_005",
  "traceId": "trace_001"
}
```

### 6.5.1 获取 speaker reference embedding

```http
POST /internal/speakers/reference-embeddings
```

该接口由 `ai-worker` 在 `SPEAKER_MATCHING` 步骤同步调用 `meeting-api`，按 personId 获取当前 active enrollment 的 L2 归一化质心向量。请求使用 internal TLS + HMAC，必带 `X-Tenant-Id`、`X-Timestamp`、`X-Nonce`、`X-Signature`、`X-Request-Id`、`X-Trace-Id`。

请求：

```json
{
  "tenantId": "t_001",
  "personIds": ["person_001", "person_002"]
}
```

响应：

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "personId": "person_001",
        "dimension": 192,
        "values": [0.012, -0.034, 0.056],
        "hash": "sha256:reference_centroid_hash",
        "computedAt": "2026-05-20T01:00:00Z"
      }
    ]
  },
  "error": null,
  "requestId": "req_internal_001",
  "traceId": "trace_001"
}
```

校验顺序固定为：签名 → 时间戳窗口 → header/body tenant 一致 → JSON 解码 → personIds 去重 → service。未知、已撤销或全 revoked 的 personId 默认省略；全量不可用返回稳定错误码 `SPEAKER_REFERENCE_UNAVAILABLE`。Java 侧只允许日志输出 count 与 hash，明文向量在调用结束后清零；worker 侧最多缓存 60 秒。

### 6.6 标记 worker 阶段完成

```http
POST /internal/processing-tasks/{taskId}/complete
```

请求体 schema 为 `CompleteWorkerPhaseRequest`。该 callback 只表示 `phase=WORKER_DAG` 的 worker DAG 阶段完成，不表示整个 task 已进入 `SUCCEEDED`。

请求：

```json
{
  "tenantId": "t_001",
  "meetingId": "m_001",
  "taskId": "task_001",
  "attemptNo": 1,
  "phase": "WORKER_DAG",
  "status": "SUCCEEDED",
  "completedSteps": [
    "AUDIO_PREPROCESS",
    "ASR",
    "DIARIZATION",
    "SPEAKER_EMBEDDING",
    "SPEAKER_MATCHING",
    "TRANSCRIPT_MERGE",
    "RAG_INDEXING"
  ],
  "skippedSteps": [
    {
      "stepName": "ALIGNMENT",
      "reason": "NOT_REQUIRED"
    }
  ],
  "artifactManifestId": "artifact_manifest_001",
  "finishedAt": "2026-05-11T06:55:00Z"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "accepted": true,
    "taskId": "task_001",
    "status": "SUCCEEDED",
    "outboxEventId": "evt_001"
  },
  "error": null,
  "requestId": "req_cb_006",
  "traceId": "trace_001"
}
```

### 6.7 标记失败

```http
POST /internal/processing-tasks/{taskId}/fail
```

请求：

```json
{
  "tenantId": "t_001",
  "meetingId": "m_001",
  "taskId": "task_001",
  "attemptNo": 1,
  "failedStep": "ASR",
  "error": {
    "code": "ASR_MODEL_TIMEOUT",
    "message": "ASR model inference timeout",
    "retryable": true,
    "details": {
      "timeoutMs": 120000,
      "audioUri": "oss://meeting-audio-auska/tenant/t_001/meeting/m_001/raw/file.wav"
    }
  },
  "artifactManifestId": "artifact_manifest_error_001",
  "failedAt": "2026-05-11T06:45:00Z"
}
```

响应：

```json
{
  "success": true,
  "data": {
    "accepted": true,
    "taskId": "task_001",
    "status": "FAILED",
    "retryable": true,
    "nextRetryAttemptNo": 2
  },
  "error": null,
  "requestId": "req_cb_006",
  "traceId": "trace_001"
}
```

### 6.8 Callback 失败响应样例

HMAC 校验失败：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "CALLBACK_AUTH_FAILED",
    "message": "Invalid callback signature",
    "retryable": false,
    "details": {
      "taskId": "task_001"
    }
  },
  "requestId": "req_cb_bad_sig",
  "traceId": "trace_001"
}
```

attempt 冲突：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "TASK_ATTEMPT_CONFLICT",
    "message": "Callback attempt does not match current task attempt",
    "retryable": false,
    "details": {
      "callbackAttemptNo": 1,
      "currentAttemptNo": 2
    }
  },
  "requestId": "req_cb_attempt_conflict",
  "traceId": "trace_001"
}
```

lease 冲突：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "TASK_LEASE_CONFLICT",
    "message": "Callback lease owner is no longer active",
    "retryable": false,
    "details": {
      "callbackLeaseOwner": "lease_old",
      "currentLeaseOwner": "lease_new"
    }
  },
  "requestId": "req_cb_lease_conflict",
  "traceId": "trace_001"
}
```

版本冲突：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VERSION_CONFLICT",
    "message": "Callback input version is stale",
    "retryable": false,
    "details": {
      "expectedTranscriptVersion": 2,
      "currentTranscriptVersion": 3
    }
  },
  "requestId": "req_cb_version_conflict",
  "traceId": "trace_001"
}
```

## 7. `ai-worker` 内部管理 API

`ai-worker` 的 FastAPI 只用于内部管理、健康检查、模型状态和 workflow 调试，不作为客户主产品入口。

### 7.1 健康检查

```http
GET /internal/health
```

响应：

```json
{
  "status": "UP",
  "workerId": "worker_gpu_001",
  "version": "2026.05.1",
  "time": "2026-05-11T06:30:00Z",
  "dependencies": {
    "rabbitmq": "UP",
    "tos": "UP",
    "modelRuntime": "UP",
    "meetingApiCallback": "UP"
  }
}
```

### 7.2 模型状态

```http
GET /internal/models
```

响应：

```json
{
  "models": [
    {
      "capability": "ASR",
      "modelName": "Qwen3-ASR",
      "modelVersion": "local-asr-v1",
      "checksum": "model_hash_001",
      "status": "LOADED",
      "device": "cuda:0",
      "loadedAt": "2026-05-11T06:00:00Z"
    },
    {
      "capability": "DIARIZATION",
      "modelName": "pyannote-community",
      "modelVersion": "local-diar-v1",
      "checksum": "model_hash_002",
      "status": "LOADED",
      "device": "cuda:0",
      "loadedAt": "2026-05-11T06:00:00Z"
    }
  ]
}
```

### 7.3 Workflow 查询

```http
GET /internal/workflows/{taskId}
```

`workflowId` 由 `ai-worker` 的 WorkflowEngine 生成。同一个 `taskId` 在不同 `attemptNo` 下可以对应不同 `workflowId`；`taskId` 是跨 attempt 稳定的业务任务 id，前端只使用 `taskId`。

响应：

```json
{
  "taskId": "task_001",
  "workflowId": "wf_001",
  "status": "RUNNING",
  "currentStep": "ASR",
  "attemptNo": 1,
  "startedAt": "2026-05-11T06:33:00Z",
  "steps": [
    {
      "stepName": "AUDIO_PREPROCESS",
      "status": "SUCCEEDED",
      "durationMs": 60000
    },
    {
      "stepName": "ASR",
      "status": "RUNNING",
      "progress": 42
    }
  ]
}
```

## 8. TOS URI 与 Artifact Manifest

TOS URI 统一格式：

```text
oss://{bucket}/tenant/{tenantId}/meeting/{meetingId}/{category}/{objectName}
```

常用路径：

```text
oss://meeting-audio-auska/tenant/t_001/meeting/m_001/raw/file.wav
oss://meeting-audio-auska/tenant/t_001/meeting/m_001/normalized/task_001.wav
oss://meeting-artifacts/tenant/t_001/meeting/m_001/artifacts/asr/task_001.json
oss://meeting-artifacts/tenant/t_001/meeting/m_001/artifacts/diarization/task_001.json
oss://meeting-exports/tenant/t_001/meeting/m_001/exports/export_001.pdf
```

Artifact manifest JSON：

```json
{
  "artifactManifestId": "artifact_manifest_001",
  "tenantId": "t_001",
  "meetingId": "m_001",
  "taskId": "task_001",
  "input": {
    "audioFileId": "file_001",
    "audioUri": "oss://meeting-audio-auska/tenant/t_001/meeting/m_001/raw/file.wav",
    "audioSha256": "audio_hash_001",
    "transcriptVersion": 1
  },
  "outputs": [
    {
      "artifactType": "RAW_ASR_JSON",
      "artifactUri": "oss://meeting-artifacts/tenant/t_001/meeting/m_001/artifacts/asr/task_001.json",
      "sha256": "artifact_hash_001",
      "sizeBytes": 1048576
    }
  ],
  "models": [
    {
      "capability": "ASR",
      "provider": "LOCAL",
      "modelName": "Qwen3-ASR",
      "configuredModel": "local-asr-v1",
      "actualModelVersion": "local-asr-v1",
      "checksum": "model_hash_001"
    }
  ],
  "pipeline": {
    "pipelineVersion": "2026.05.1",
    "codeVersion": "git_sha_001",
    "dataBoundaryPolicyVersion": "data-boundary-2026.05.1"
  },
  "createdAt": "2026-05-11T06:55:00Z"
}
```

## 9. LLM Gateway 请求记录

`llm-gateway` 是 `meeting-api` 内部业务域，但所有 LLM 调用都必须落审计日志。调用 DashScope 的输入输出 hash 和实际模型版本必须可追溯。

LLM 调用日志 JSON：

```json
{
  "llmCallLogId": "llm_call_001",
  "tenantId": "t_001",
  "meetingId": "m_001",
  "capability": "MEETING_MINUTES",
  "provider": "DASHSCOPE",
  "configuredModel": "qwen-plus",
  "actualModelVersion": "qwen-plus-2026-05-01",
  "promptTemplateId": "meeting_minutes_zh",
  "promptTemplateVersion": "2026.05.1",
  "securityLevel": "INTERNAL",
  "textRedactionBeforeThirdPartyLlm": false,
  "dataBoundaryPolicyVersion": "data-boundary-2026.05.1",
  "artifactManifestId": "artifact_manifest_minutes_001",
  "inputHash": "input_hash_001",
  "outputHash": "output_hash_001",
  "latencyMs": 8200,
  "tokenUsage": {
    "promptTokens": 12000,
    "completionTokens": 1800,
    "totalTokens": 13800
  },
  "createdAt": "2026-05-11T07:00:00Z"
}
```

LLM 结构化输出必须通过 JSON Schema 校验。纪要结果示例：

```json
{
  "summary": "本次会议确认六月底上线目标不变，但供应商确认和预算缺口是关键风险。",
  "decisions": [
    {
      "title": "六月底上线目标不变",
      "status": "PROPOSED",
      "evidence": [
        {
          "segmentId": "seg_001",
          "startMs": 13000,
          "endMs": 28000,
          "evidenceTextSnapshot": "如果要按六月底上线，需要这周确认供应商。"
        }
      ]
    }
  ],
  "actionItems": [
    {
      "title": "确认供应商",
      "assigneeDisplayName": "李四",
      "dueDate": "2026-05-15",
      "status": "SUGGESTED",
      "evidence": [
        {
          "segmentId": "seg_001",
          "startMs": 13000,
          "endMs": 28000,
          "evidenceTextSnapshot": "需要这周确认供应商。"
        }
      ]
    }
  ],
  "risks": [
    {
      "title": "预算存在二十万缺口",
      "severity": "HIGH",
      "status": "OPEN",
      "evidence": [
        {
          "segmentId": "seg_001",
          "startMs": 13000,
          "endMs": 28000,
          "evidenceTextSnapshot": "预算这块目前还有二十万缺口。"
        }
      ]
    }
  ]
}
```

## 10. 幂等、并发与版本

1. 所有写接口必须支持 `Idempotency-Key`，相同 key 重放时返回同一业务结果。
2. callback 必须校验 `X-Attempt-No` 与当前 task attempt 一致。
3. callback 必须校验 `X-Lease-Owner` 与当前 lease owner 一致；旧 lease 的迟到 callback 必须拒绝或进入幂等冲突处理。
4. 修改转录、speaker、纪要、RAG chunk 时必须携带 `expectedTranscriptVersion` 或对应 `expected*Version`。
5. 重建任务完成时，如果当前版本与 `expected*Version` 不一致，结果不得覆盖当前 ACTIVE 版本，只能标记为过期产物并记录审计。

callback `Idempotency-Key` 精确定义：

```text
{taskId}:{stepName}:{attemptNo}:{payloadVersion}
```

字段含义：

1. `taskId` 是 Java 创建的稳定业务任务 id。
2. `stepName` 是 `ProcessingStep` 枚举值。
3. `attemptNo` 是当前 worker attempt，必须与 `X-Attempt-No` 一致。
4. `payloadVersion` 从 `v1` 开始；同一 attempt 内 payload 语义结构变化时递增。普通 heartbeat / progress 可复用版本，`complete`、`fail`、`transcript`、`speaker-candidates` 和 `embeddings` 必须使用新的版本。

服务端持久化 `idempotency_key`、`request_body_hash`、`http_status`、`response_body`、`processed_at`，保留默认 `30d`。相同 key 且 body hash 一致时直接返回缓存响应；相同 key 但 body hash 不一致时返回 `CALLBACK_IDEMPOTENCY_CONFLICT`。

幂等冲突响应：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "IDEMPOTENCY_CONFLICT",
    "message": "Idempotency key was already used with different payload",
    "retryable": false,
    "details": {
      "idempotencyKey": "task_001:ASR:attempt_1:v1"
    }
  },
  "requestId": "req_conflict_001",
  "traceId": "trace_001"
}
```

## 11. 安全与数据边界

1. `PUBLIC` / `INTERNAL` 会议可调用 DashScope，必须记录安全等级、调用审计和输入 / 输出 hash。
2. `CONFIDENTIAL` / `SECRET` 会议的一期自动 LLM step 必须返回 `SECURITY_LEVEL_BLOCKED`。
3. 原始音频、标准化音频、声纹参考音频、声纹 embedding、声纹模型原始输出不得发送给 DashScope。
4. 声纹 embedding 必须应用层信封加密存储；数据库不存明文 float 数组，不建立明文 pgvector 索引。
5. internal API 与 public API 必须使用独立路由前缀、独立鉴权 filter 和独立审计日志。
6. 生产阶段升级为 mTLS + 短期 service JWT，HMAC 可作为兼容方案保留。

安全等级阻断示例：

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "SECURITY_LEVEL_BLOCKED",
    "message": "一期不支持该安全等级的自动 LLM 处理",
    "retryable": false,
    "details": {
      "securityLevel": "SECRET",
      "taskId": "task_001",
      "stepName": "SUMMARY"
    }
  },
  "requestId": "req_017",
  "traceId": "trace_001"
}
```

## 12. 错误码分类

事实来源：完整错误码字典、retryable 默认值、用户提示、运维标签和 i18n key 以 `packages/meeting-contracts/schemas/common/error-codes.yaml` 为准。本节只给责任域分组，避免实现方在各自工程维护第二份错误码。

错误码必须稳定，不直接暴露底层异常类名。

| 分类 | 示例错误码 | retryable |
|---|---|---|
| Auth | `AUTH_REQUIRED`, `PERMISSION_DENIED`, `TENANT_CONTEXT_MISSING` | false |
| Validation | `VALIDATION_FAILED`, `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT` | false |
| Task | `TASK_NOT_FOUND`, `TASK_ATTEMPT_CONFLICT`, `TASK_LEASE_CONFLICT` | false |
| Storage | `OSS_OBJECT_NOT_FOUND`, `OSS_READ_FAILED`, `OSS_WRITE_FAILED` | true |
| AI Pipeline | `ASR_MODEL_TIMEOUT`, `DIARIZATION_FAILED`, `SPEAKER_MATCH_FAILED` | true |
| RAG | `RAG_INDEX_FAILED`, `VECTOR_SEARCH_FAILED`, `RERANK_UNAVAILABLE`, `RERANK_CONTRACT_ERROR` | depends |
| LLM | `SECURITY_LEVEL_BLOCKED`, `LLM_SCHEMA_INVALID`, `LLM_PROVIDER_TIMEOUT` | depends |
| Export | `EXPORT_RENDER_FAILED`, `EXPORT_LINK_REVOKED` | depends |

统一错误结构：

```json
{
  "code": "LLM_SCHEMA_INVALID",
  "message": "LLM output failed JSON Schema validation",
  "retryable": true,
  "details": {
    "schemaId": "meeting-minutes-v1",
    "artifactManifestId": "artifact_manifest_minutes_001"
  }
}
```

## 13. 契约演进规则

1. 新增字段必须保持向后兼容，旧客户端忽略未知字段不得出错。
2. 删除字段、改字段语义、改枚举语义必须升 major version。
3. API 文档、OpenAPI、JSON Schema、前后端 DTO 必须同步更新。
4. RabbitMQ 消息必须保留 `taskId`、`taskType`、`tenantId`、`traceId` 四个最小必填字段。
5. callback body 中的 `tenantId`、`meetingId`、`taskId`、`attemptNo` 不得改名或降级为可选。
6. 所有跨应用契约变更必须增加 contract test，至少覆盖 JSON Schema 校验、反序列化和错误响应。
