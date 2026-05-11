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

### 2.1 技术选型基线

一期选型固定如下，除非单独更新本 SPEC 和父 POM：

| 类别 | 选型 | 最低版本 | 升级触发 / 审批 | 约束 |
|---|---|---|---|---|
| 构建 | Maven 多模块 | 3.9.x | 父 POM 维护人审批 | 父 POM 统一版本，模块不得各自引入冲突版本 |
| Java | 17 LTS | 17.0.10+ | 安全补丁自动评估，主版本升级需架构评审 | `maven-compiler-plugin` 使用 `release=17` |
| Spring Boot | 3.3.x | 3.3.5+ | CVE、Spring Cloud 兼容性或依赖冲突触发 | 与当前父 POM 对齐，不混用 Boot 2.x 依赖 |
| ORM / SQL | MyBatis-Plus 3.5.x + 原生 SQL | 3.5.7+ | SQL 能力或安全补丁触发 | RLS、`FOR UPDATE SKIP LOCKED`、pgvector 查询优先写显式 SQL；不引入 JPA |
| Migration | Flyway 10.x | 10.17+ | PostgreSQL 版本升级或迁移能力需要 | 路径 `src/main/resources/db/migration/V{yyyyMMddHHmm}__desc.sql` |
| 连接池 | HikariCP | 随 Spring Boot BOM | 性能回归或连接泄漏修复触发 | `maximumPoolSize=20` 起步，连接归还前 reset tenant context |
| JSON | Jackson 2.17.x | 2.17.2+ | CVE 或 OpenAPI 兼容性触发 | camelCase、ISO-8601 UTC、未知字段按契约策略处理 |
| 校验 | Jakarta Bean Validation 3.x | 随 Spring Boot BOM | 框架升级触发 | Controller 基础校验，业务语义校验放 app / domain |
| 日志 | Logback + Logstash JSON encoder | BOM 对齐 | 安全补丁触发 | MDC 必须包含 `traceId`、`requestId`、`tenantId`、`userId` |
| 测试 | JUnit 5 + Mockito + ArchUnit + Testcontainers + WireMock | BOM 对齐 | CI 稳定性或能力缺口触发 | Testcontainers 覆盖 PostgreSQL / RabbitMQ / MinIO 替身 |
| 度量 | Micrometer + Prometheus | BOM 对齐 | 指标兼容性触发 | actuator 暴露 `health`、`metrics`、`prometheus`、`info` |
| API 文档 | springdoc-openapi 2.x | 2.6+ | OpenAPI 契约变更触发 | 生成结果必须与 `packages/meeting-contracts/openapi` 语义一致 |

### 2.2 模块依赖与 CI 守卫

每个子模块的 POM 只允许声明本模块需要的依赖：

1. `meeting-api-domain`：只依赖 `meeting-api-client` 和纯 Java 工具，不依赖 Spring Web、JDBC、AMQP、TOS SDK、DashScope SDK。
2. `meeting-api-client`：只放 DTO / Command / Query / Result / Facade / enum / error code，不依赖数据库、Web、MQ、外部 SDK。
3. `meeting-api-app`：可依赖 `client`、`domain`、Spring transaction / validation，不依赖具体 mapper、HTTP SDK、AMQP client。
4. `meeting-api-infrastructure`：实现 Repository / Gateway，可依赖 MyBatis-Plus、JDBC、TOS、RabbitMQ、DashScope、KMS、LibreOffice adapter。
5. `meeting-api-adapter`：依赖 Web / validation / security adapter，只做协议适配，不依赖 mapper。
6. `meeting-api-start`：聚合启动依赖，不写业务逻辑。

CI 必须增加 ArchUnit 规则：domain 禁止 import `org.springframework.web..`、`org.springframework.jdbc..`、`com.baomidou..`、`com.rabbitmq..`；adapter 禁止访问 mapper package；app 禁止访问 infrastructure implementation package。

ArchUnit 落地测试类固定为 `meeting-api-start/src/test/java/com/meeting/api/ArchitectureBoundaryTest.java`。一期阶段规则失败级别为 `ERROR`，只允许对尚未实现模块使用带到期日期的 `@ArchIgnore`，不得长期以 WARN 绕过。

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
4. `task` 创建 `MEETING_FULL_PIPELINE` processing task，并将 Java-owned `AUDIO_UPLOAD` step 初始化为 `SUCCEEDED`。
5. `task` 发布 outbox 事件，outbox publisher 投递 RabbitMQ。
6. `ai-worker` 消费任务并回写 step、artifact、transcript、speaker candidates 和 `phase=WORKER_DAG` 完成状态；它不负责整个 task 终态。
7. `adapter` 接收 callback，`app` 校验幂等、attempt、lease、tenant 和 meeting 关系。
8. `meeting` 落库结构化转录。
9. `app` 在确认 worker phase 完成的同一事务内推进 `processing_tasks.phase=WORKER_DAG_DONE`，写出包含 `taskType` 的 `WORKER_PHASE_COMPLETED` outbox；callback 响应不等待 LLM。
10. app 层 listener 异步消费 `WORKER_PHASE_COMPLETED`，通过 `TaskStepProgressService` 将 `processing_tasks.phase=JAVA_LLM_RUNNING`，并将 `SUMMARY` step 标记为 `RUNNING`；`llm-gateway` 生成纪要后标记 `SUCCEEDED` / `FAILED`，并发布 `TASK_STEP_UPDATED` SSE。
11. Java `task` 模块同样推进 `EXTRACTION` step，生成待办、决策、风险后标记 `SUCCEEDED` / `FAILED`，最终将 `processing_tasks.phase=TERMINAL` 并发布 task 终态事件。
12. `ai-worker` 不参与 `SUMMARY` / `EXTRACTION` 的 step 推进。
13. `rag` 将转录、纪要和结构化事项入库为 chunk。
14. outbox publisher 投递成功后发布 `TASK_STARTED` / `TASK_STEP_UPDATED` SSE 事件；推送失败不得回滚业务事务，但必须进入 outbox 重试和告警。

`TaskStepProgressService` 是 `meeting-api-app` 的 app 层 task service，不是 domain port。实现放在 `meeting-api-app/src/main/java/com/meeting/api/app/task/`，通过 `ProcessingTaskRepository` 推进 step 状态，并与 outbox 写入处于同一应用事务边界内。

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

事实来源：request / response / SSE schema 以 `packages/meeting-contracts/openapi/public-api.yaml` 为准；枚举与错误码分别以 `packages/meeting-contracts/schemas/common/enums.yaml`、`packages/meeting-contracts/schemas/common/error-codes.yaml` 为准。本节只描述 `meeting-api` 的鉴权、权限、事务和落地边界。

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

事实来源：callback endpoint、请求头、签名字段、body schema 和错误响应以 `packages/meeting-contracts/openapi/internal-callback-api.yaml` 为准；本节只描述 Java 接收端的校验顺序和业务写入边界。

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
8. `tenantId`、`taskId` 和业务对象关系按 task 类型校验：`MEETING_FULL_PIPELINE` 要求 `meetingId` 非空且匹配；`TEXT_EMBEDDING` / `RAG_REINDEX` 要求 `meetingId` 与 `documentId` 至少一个非空且归属当前 tenant；`SPEAKER_ENROLLMENT` 允许 `meetingId=null`，但必须校验 speaker profile / enrollment 归属。
9. `expectedInputVersion` 不落后于当前可接受版本。

旧 attempt 或旧 lease 的迟到 callback 不得覆盖新 attempt 结果。

`PATCH /internal/processing-tasks/{taskId}/steps/{stepName}` 中 `status=RUNNING && progress>0` 是 heartbeat / progress update，不写 `callback_events`，不做 body hash 幂等冲突判定；只校验 attempt、lease 和 tenant 后按 latest-wins 更新进度。首次 `RUNNING(progress=0)`、`SUCCEEDED`、`FAILED` 仍走普通幂等表。

HMAC `signing_string` 的 `URL_PATH_WITH_QUERY` 必须使用原始 URI，包含 `/internal` server prefix。adapter 传给 app 层的验签上下文不得使用丢失 servlet path 后的相对路径。

## 7. 数据与事务

事实来源：表、字段、索引和 RLS policy 以 `docs/ddls/001_initial_schema.sql` 为准；错误码字典以 `packages/meeting-contracts/schemas/common/error-codes.yaml` 为准。本节只约束 Java 侧事务、tenant context 和 outbox 行为。

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

事务边界：

1. `@Transactional` 默认使用 `REQUIRED`；非默认 propagation 必须在方法注释或类注释中说明原因。
2. 涉及 outbox 写入的用例必须 `REQUIRED`，业务数据和 outbox 同事务提交。
3. callback 处理最外层使用 `REQUIRES_NEW`，避免上层协议异常回滚已确认的幂等响应。
4. 长耗时外部调用、文件上传、LLM 调用、LibreOffice 转换不得包在数据库事务内；只在调用前后各自开启短事务。
5. 默认使用 optimistic locking / version 列处理用户编辑冲突；声纹 enrollment 和同一 speaker profile 的 centroid 更新使用 `SELECT ... FOR UPDATE`。
6. outbox publisher 使用 `SELECT ... FOR UPDATE SKIP LOCKED` 扫描未发布事件，单批默认 100 条。

事务隔离级别：

| 场景 | 隔离级别 | 锁策略 |
|---|---|---|
| 普通查询和列表 | `READ_COMMITTED` | 依赖 RLS 和权限过滤，不持有业务锁 |
| 用户编辑转录、纪要和事项 | `READ_COMMITTED` | optimistic locking / version 列 |
| callback 幂等落库 | `READ_COMMITTED` | `callback_events.idempotency_key` 唯一约束，必要时锁定 task 行 |
| worker lease / outbox 扫描 | `READ_COMMITTED` | `FOR UPDATE SKIP LOCKED` |
| speaker centroid 更新、撤销授权级联 | `READ_COMMITTED` | 对同一 profile 使用 `SELECT ... FOR UPDATE` |
| deletion job 计划生成 | `READ_COMMITTED` | 先锁定 deletion job，再检查 legal hold；禁止长事务包围物理删除 |

异常映射由 `ControllerAdvice` 查表完成，不在 Controller 中拼响应：

| 领域异常 | ErrorCode | HTTP |
|---|---|---:|
| `AuthenticationRequiredException` | `AUTH_REQUIRED` | 401 |
| `PermissionDeniedException` | `PERMISSION_DENIED` | 403 |
| `TenantContextMissingException` | `TENANT_CONTEXT_MISSING` | 403 |
| `ValidationException` | `VALIDATION_FAILED` | 422 |
| `VersionConflictException` | `VERSION_CONFLICT` | 409 |
| `IdempotencyConflictException` | `IDEMPOTENCY_CONFLICT` | 409 |
| `CallbackAuthException` | `CALLBACK_AUTH_FAILED` | 401 |
| `TaskAttemptConflictException` | `TASK_ATTEMPT_CONFLICT` | 409 |
| `TaskLeaseConflictException` | `TASK_LEASE_CONFLICT` | 409 |
| `SecurityLevelBlockedException` | `SECURITY_LEVEL_BLOCKED` | 422 |
| `LegalHoldBlockedException` | `LEGAL_HOLD_BLOCKED` | 423 |
| `ExternalDependencyUnavailableException` | `DEPENDENCY_UNAVAILABLE` | 503 |

## 7.1 业务域代码定位约定

以 `meeting` 域为模板，所有业务域按同一结构放置：

```text
meeting-api-adapter/src/main/java/com/meeting/api/adapter/meeting/MeetingController.java
meeting-api-adapter/src/main/java/com/meeting/api/adapter/meeting/MeetingBffController.java
meeting-api-app/src/main/java/com/meeting/api/app/meeting/command/CreateMeetingCmdExe.java
meeting-api-app/src/main/java/com/meeting/api/app/meeting/query/GetMeetingDetailQryExe.java
meeting-api-domain/src/main/java/com/meeting/api/domain/meeting/Meeting.java
meeting-api-domain/src/main/java/com/meeting/api/domain/meeting/MeetingRepository.java
meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/persistence/meeting/MeetingMapper.java
meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/persistence/meeting/MeetingRepositoryImpl.java
```

`task`、`speaker`、`rag`、`document`、`export`、`compliance`、`audit` 等域使用同样命名，避免跨域类散落到 `common`。

`api/bff` 不作为独立业务域写入 domain；它是 adapter 层的视图聚合边界，代码放在 `meeting-api-adapter/src/main/java/com/meeting/api/adapter/bff/` 或各业务 adapter 的 `*BffController` 中，实际查询仍调用 app query service。

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

## 9. 性能与 SLO

Java 侧 SLO 以局域网办公环境、PostgreSQL / RabbitMQ / TOS 可用、缓存预热后为基线。LLM 和 ai-worker 长耗时计算不计入普通 API SLO，但进入对应异步任务和依赖调用指标。

| 指标 | 目标 | 告警 | 观测点 |
|---|---:|---:|---|
| `GET /api/meetings` p95 | `<= 200ms` | `>= 500ms` 连续 5 分钟 | `http.server.requests` + endpoint tag |
| `GET /api/meetings/{meetingId}/transcript` p95 | `<= 300ms` 首页 | `>= 800ms` | transcript 分页查询耗时 |
| `POST /api/rag/query` p95 | `<= 6s` | `>= 10s` | RAG 总耗时，含检索和 DashScope |
| callback 处理 p95（无 LLM） | `<= 150ms` | `>= 500ms` | `/internal/processing-tasks/**` |
| outbox 发布 lag | `<= 5s` | `>= 30s` | `now - created_at` for unpublished events |
| outbox backlog | `< 1000` 条 | `>= 5000` 条 | `domain_events_outbox status=PENDING` |
| SSE 首字节 | `<= 1s` | `>= 3s` | 建连到首个 `TASK_SNAPSHOT` |
| SSE 单 task 推送延迟 p95 | `<= 500ms` | `>= 2s` | event created 到 emitted |
| upload session 初始化 p95 | `<= 300ms` | `>= 1s` | 不包含客户端直传 TOS |
| signed URL 生成 p95 | `<= 200ms` | `>= 800ms` | TOS SDK 调用耗时 |

性能落地要求：

1. 每个 public endpoint、callback endpoint、outbox publisher、SSE emitter 必须打 Micrometer timer / counter，并带 `tenantId` 高基数字段的脱敏或采样策略。
2. RAG 查询必须拆分记录 scope 计算、vector search、keyword search、permission recheck、LLM 调用和 response assembly 耗时。
3. outbox publisher lag 超过告警阈值时，应停止只看 RabbitMQ 可用性，必须同时检查 DB 锁等待、publisher 异常和消息确认耗时。
4. callback p95 超过阈值时，优先排查幂等表唯一键冲突、task 行锁、RLS policy 和 artifact 大字段写入。
5. SLO 目标变更必须同步 `infra/meeting-infra` 告警规则和 dashboard。

## 10. 验收标准

1. 完成登录、租户隔离、会议创建、音频上传和任务创建。
2. RabbitMQ 消息包含 task、tenant、meeting、audio URI、security level、attempt、`pipelineSteps`、版本和 trace，且不得把 `AUDIO_UPLOAD` / `SUMMARY` / `EXTRACTION` 分配给 `ai-worker`。
3. callback 支持幂等重放，旧 attempt 不能覆盖新结果。
4. 转录落库区分 `original_text`、`edited_text`、`current_text`。
5. 编辑转录后纪要、事项和 RAG chunk 标记 STALE。
6. PUBLIC / INTERNAL 能通过 llm-gateway 生成纪要和结构化事项。
7. CONFIDENTIAL / SECRET 自动 LLM 返回 `SECURITY_LEVEL_BLOCKED`。
8. RAG 检索只返回有权限且 ACTIVE 的 chunk，并带 citation。
9. 导出任务异步执行，绑定输入版本，文件写入 TOS。
10. legal hold 阻止生命周期删除，deletion job 完成后生成 certificate。
11. heartbeat callback 重放或连续上报不因 body hash 不同返回 409。
12. `SUMMARY` / `EXTRACTION` step 由 Java 推进，前端可通过 SSE 看到 `TASK_STEP_UPDATED`。
13. `/complete phase=WORKER_DAG` callback 中的 `skippedSteps` 会写入 worker-owned `processing_task_steps.status=SKIPPED`，但不会直接把 task 推进到终态。
14. `meetings.status` 按 `CREATED -> PROCESSING -> SUCCEEDED / FAILED -> DELETED` 状态机推进；全量 rebuild 允许 `SUCCEEDED -> PROCESSING` 但一期仅 internal-only 运维触发，不提供 public API 或前端入口；局部 regenerate / reindex 不改变 meeting status，legal hold 命中时删除返回 423。
