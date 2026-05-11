# meeting-api Spec

## 1. 工程定位

`meeting-api` 是一期系统的主产品入口、业务事实来源、权限来源、任务编排方和统一 UI 后端。工程采用 Java 17 + Spring Boot + 阿里 COLA-V5 模块化单体，不在一期拆成多个 Java 微服务。

职责：

1. 提供 `meeting-web` 使用的 Public API 和 SSE。
2. 管理用户、租户、会议、转录、纪要、事项、文档、RAG、导出、声纹、审计和合规对象。
3. 创建异步任务并投递 RabbitMQ。
4. 接收 `ai-worker` internal callback，校验 HMAC、租户、任务、attempt、lease 和幂等。
5. 统一通过 `llm-gateway` 调用 DashScope，集中做安全等级、Prompt、结构化输出和审计。
6. 通过 PostgreSQL RLS 实现租户隔离，通过 outbox 保证业务状态和事件发布同事务提交。

## 2. COLA-V5 模块边界

| 模块 | 职责 |
|---|---|
| `meeting-api-start` | Spring Boot 启动、配置装配、profile、健康检查、组件扫描 |
| `meeting-api-client` | DTO、Command、Query、Result、Facade、枚举、错误码契约 |
| `meeting-api-adapter` | REST Controller、SSE、internal callback、BFF 响应适配、`export-queue` consumer |
| `meeting-api-app` | 应用服务、用例编排、事务边界、租户上下文、权限编排、outbox 发布 |
| `meeting-api-domain` | 聚合、实体、值对象、领域服务、领域事件、Repository / Gateway 端口 |
| `meeting-api-infrastructure` | PostgreSQL / pgvector、TOS、RabbitMQ、DashScope、LibreOffice、KMS、签名 URL、外部网关实现 |

依赖方向：

```text
adapter -> app / client
app -> domain / client
infrastructure -> domain / client
start -> adapter / app / infrastructure
domain 不依赖 adapter、app、infrastructure
```

## 3. 业务域

业务域不是独立服务，而是各 COLA 模块内的 package 边界。

| 业务域 | 一期能力 |
|---|---|
| `api/bff` | 鉴权、限流、响应聚合、前端视图模型 |
| `user-auth` | 内置账号、登录退出、用户、角色、租户、用户与 person 绑定 |
| `meeting` | 会议生命周期、参会人、音频文件、转录、纪要、待办、决策、风险 |
| `task` | 长任务、step、lease、heartbeat、重试、取消、DLQ、幂等 callback |
| `storage` | TOS 元信息、分片上传、签名 URL、文件生命周期 |
| `llm-gateway` | DashScope provider、Prompt 版本、结构化输出、审计、fail closed |
| `speaker` | 声纹档案、授权、enrollment、候选匹配、人工确认、撤销级联 |
| `rag` | chunk 入库、pgvector 检索、权限过滤、citation、缓存失效 |
| `document` | 文档上传、文本抽取、chunk、reindex |
| `export` | Markdown / DOCX / PDF 异步导出、短链撤销、版本绑定 |
| `audit` | 处理、查看、导出、权限、声纹访问、break-glass 审计 |
| `compliance` | legal hold、deletion job、deletion certificate |

## 4. 核心流程

### 4.1 会议音频处理

1. `meeting-web` 创建会议。
2. `storage` 创建 TOS multipart upload 或签名 URL。
3. 上传完成后，`meeting` 保存音频文件元信息。
4. `task` 创建 `MEETING_FULL_PIPELINE` processing task。
5. `task` 发布 outbox 事件，outbox publisher 投递 RabbitMQ。
6. `ai-worker` 消费任务并回写 step、artifact、transcript、speaker candidates 和终态。
7. `adapter` 接收 callback，`app` 校验幂等、attempt、lease、tenant 和 meeting 关系。
8. `meeting` 落库结构化转录。
9. `llm-gateway` 生成纪要、待办、决策、风险。
10. `rag` 将转录、纪要和结构化事项入库为 chunk。

### 4.2 文档知识库

1. `document` 接收上传并保存 TOS 文件元信息。
2. Java 使用 Apache Tika 或同类 JVM 库解析可提取文本的 PDF、DOCX、TXT、Markdown。
3. 不支持扫描 PDF OCR 和图片 OCR，必须返回明确提示。
4. `document` 切 chunk 并创建 embedding 任务。
5. `ai-worker` 生成 embedding 并 callback。
6. `rag` 写入 `knowledge_chunks`，后续可被 RAG 查询召回。

### 4.3 RAG 问答

1. API 入口鉴权并设置 tenant context。
2. `rag` 实时计算用户可访问 scope，不能把向量库作为权限事实来源。
3. 执行 metadata filter、pgvector 检索和关键字召回。
4. 检索结果返回前再次经过 PostgreSQL 权限校验。
5. 过滤 `status != ACTIVE` 或 `stale_status != ACTIVE` 的 chunk。
6. 组装上下文和 citation。
7. `llm-gateway` 按安全等级调用 DashScope 或 fail closed。
8. 保存 `rag_query_logs`、`llm_call_logs` 和 `artifact_manifest` 关联。

## 5. Public API

Public API 路由前缀为 `/api`。所有接口必须经过登录态鉴权、tenant context 设置、权限校验、审计和限流。

| 能力 | Endpoint |
|---|---|
| 账号 | `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`、`GET /api/users`、`POST /api/users`、`PATCH /api/users/{userId}` |
| 会议 | `POST /api/meetings`、`GET /api/meetings`、`GET /api/meetings/{meetingId}`、`PATCH /api/meetings/{meetingId}`、`DELETE /api/meetings/{meetingId}` |
| 音频上传 | `POST /api/meetings/{meetingId}/files/audio/uploads`、`POST /parts`、`POST /complete`、`POST /abort`、`GET /uploads/{uploadId}` |
| 处理任务 | `POST /api/meetings/{meetingId}/processing-tasks`、`GET /latest`、`GET /api/processing-tasks/{taskId}`、`GET /events`、`POST /retry`、`POST /cancel` |
| 转录 | `GET /api/meetings/{meetingId}/transcript`、`PATCH /segments/{segmentId}`、`POST /regenerate`、`PUT /speakers/{speakerLabel}` |
| 声纹 | `POST /api/speaker-profiles`、`POST /enrollments`、`GET /speaker-profiles/{profileId}`、`POST /revoke`、`DELETE /speaker-profiles/{profileId}`、speaker confirm/reject |
| 纪要和事项 | minutes、action-items、decisions、risks 的查询、重生成、接受和拒绝 |
| 文档 | `POST /api/documents`、`GET /api/documents`、`GET /api/documents/{documentId}`、`DELETE /api/documents/{documentId}`、`POST /reindex` |
| RAG | `POST /api/rag/query`、`POST /api/rag/reindex/meetings/{meetingId}`、`POST /api/rag/reindex/documents/{documentId}` |
| 导出 | `POST /api/meetings/{meetingId}/exports`、`GET /exports`、`GET /api/exports/{exportId}`、`POST /cancel`、`POST /revoke-link` |
| 合规管理 | legal holds、deletion jobs、break-glass requests 和 audit |

## 6. Internal Callback

`ai-worker` callback Endpoint：

```http
PATCH /internal/processing-tasks/{taskId}/steps/{stepName}
POST  /internal/processing-tasks/{taskId}/artifacts
POST  /internal/processing-tasks/{taskId}/transcript
POST  /internal/processing-tasks/{taskId}/speaker-candidates
POST  /internal/processing-tasks/{taskId}/embeddings
POST  /internal/processing-tasks/{taskId}/complete
POST  /internal/processing-tasks/{taskId}/fail
```

必须校验：

1. 内网访问控制。
2. HMAC-SHA256 签名。
3. `X-Timestamp` 允许 5 分钟偏差。
4. `X-Nonce` 短期去重。
5. `Idempotency-Key` 内容一致性。
6. `X-Attempt-No` 与当前 task attempt 一致。
7. `X-Lease-Owner` 与当前 lease owner 一致。
8. `tenantId`、`taskId`、`meetingId` 关系一致。
9. `expectedInputVersion` 不落后于当前可接受版本。

旧 attempt 或旧 lease 的迟到 callback 不得覆盖新 attempt 结果。

## 7. 数据与事务

所有租户表必须：

1. 包含 `tenant_id`。
2. 启用 `ENABLE ROW LEVEL SECURITY`。
3. 启用 `FORCE ROW LEVEL SECURITY`。
4. 定义 SELECT / UPDATE / DELETE 的 `USING` policy。
5. 定义 INSERT / UPDATE 的 `WITH CHECK` policy。

事务要求：

1. 每个业务事务开始前设置 `app.tenant_id`、`app.user_id`、`app.request_id`。
2. current tenant 缺失时 fail closed。
3. 连接归还前 reset tenant context。
4. 后台任务、callback、导出任务都必须携带并设置 tenant context。
5. 领域事件写入 `domain_events_outbox` 必须与业务数据同事务提交。
6. 同一聚合的 outbox 事件按 `sequence_no` 单调递增，publisher 必须保证单聚合内有序发布。

## 8. 安全等级与 LLM

| security_level | 一期策略 |
|---|---|
| `PUBLIC` | 可调用 DashScope，记录审计 |
| `INTERNAL` | 可调用 DashScope，发送前不做文本脱敏，记录审计 |
| `CONFIDENTIAL` | 自动 LLM fail closed |
| `SECRET` | 自动 LLM fail closed |

不得发送给 DashScope：

1. 原始音频。
2. 标准化音频。
3. 声纹参考音频。
4. 声纹 embedding。
5. 声纹模型原始输出。
6. `CONFIDENTIAL` / `SECRET` 会议文本。

## 9. 验收标准

1. 完成登录、租户隔离、会议创建、音频上传和任务创建。
2. RabbitMQ 消息包含 task、tenant、meeting、audio URI、security level、attempt、版本和 trace。
3. callback 支持幂等重放，旧 attempt 不能覆盖新结果。
4. 转录落库区分 `original_text`、`edited_text`、`current_text`。
5. 编辑转录后纪要、事项和 RAG chunk 标记 STALE。
6. PUBLIC / INTERNAL 能通过 llm-gateway 生成纪要和结构化事项。
7. CONFIDENTIAL / SECRET 自动 LLM 返回 `SECURITY_LEVEL_BLOCKED`。
8. RAG 检索只返回有权限且 ACTIVE 的 chunk，并带 citation。
9. 导出任务异步执行，绑定输入版本，文件写入 TOS。
10. legal hold 阻止生命周期删除，deletion job 完成后生成 certificate。
