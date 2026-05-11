# meeting-web Spec

## 1. 工程定位

`meeting-web` 是本地会议智能系统的一期 React SPA。它只调用 `meeting-api` 的 Public API 和 SSE，不直接访问 Python、RabbitMQ、PostgreSQL、TOS 或 DashScope。

一期构建基线：

| 项 | 约束 |
|---|---|
| Runtime | Node.js `20 LTS` 起步 |
| Framework | React `18.3+`，暂不启用 React 19 专属 API |
| Build | Vite `5+` |
| Language | TypeScript `strict=true` |
| API 类型 | 优先从 `packages/meeting-contracts/openapi/public-api.yaml` 生成；手写类型必须有 schema 映射说明 |
| 包管理 | 跟随仓库统一 lockfile，不允许页面模块各自漂移 |

核心职责：

1. 承载用户主流程：登录、创建会议、上传音频、查看进度、编辑转录、确认 speaker、生成纪要、RAG 问答和导出。
2. 承载管理流程：用户管理、声纹档案、文档知识库、删除任务、删除证书、legal hold、break-glass 审批和审计。
3. 将服务端的任务状态、错误码、STALE 状态和引用来源准确展示给用户，不伪造处理结果。
4. 对 `CONFIDENTIAL` / `SECRET` 自动 LLM 阻断给出明确提示：`一期不支持该安全等级的自动 LLM 处理`。

## 2. 非职责

1. 不保存业务事实，所有事实以 `meeting-api` 返回为准。
2. 不在前端直接拼装 TOS 私有路径，只使用后端返回的上传会话、签名 URL 和下载 URL。
3. 不直接调用 `ai-worker` 内部接口。
4. 不在前端实现权限判定，只根据后端返回的权限和状态控制可见性和交互。
5. 不把声纹 embedding、声纹模型原始输出或敏感内部 artifact 展示给用户。

## 3. 页面与路由

一期至少实现下列页面。路由命名可按项目实际 Router 规范调整，但页面能力必须覆盖。

| 页面 | 建议路由 | 需要权限 / 角色 | 主要能力 |
|---|---|---|---|
| 登录页 | `/login` | 无 | 登录、错误提示、登录态初始化 |
| 会议列表 | `/meetings` | `meeting:read` | 列表、搜索、状态筛选、安全等级筛选 |
| 会议创建 | `/meetings/new` | `meeting:create` | 创建会议、选择安全等级、参会人 |
| 音频上传 | `/meetings/:meetingId/audio` | `meeting:upload-audio` | 断点续传、上传进度、取消、重试 |
| 任务进度 | `/meetings/:meetingId/tasks/:taskId` | `task:read` | SSE 步骤级进度、错误码、重试、取消 |
| 转录编辑 | `/meetings/:meetingId/transcript` | `transcript:read` / `transcript:edit` | segment 列表、编辑、版本和 STALE 提示 |
| speaker 确认 | `/meetings/:meetingId/speakers` | `speaker:confirm` | 匿名 label、候选人、置信度、确认和拒绝 |
| 声纹档案 | `/speaker-profiles` | `speaker:manage` | 档案、授权、参考音频、撤销、删除 |
| 纪要 | `/meetings/:meetingId/minutes` | `minutes:read` / `minutes:regenerate` | 纪要章节、重生成、diff 或新建议 |
| 待办/决策/风险 | `/meetings/:meetingId/items` | `action-item:read` / `action-item:edit` | AI 建议、接受、拒绝、状态和 evidence |
| 文档知识库 | `/documents` | `document:read` / `document:manage` | 上传、解析状态、reindex、删除 |
| RAG 问答 | `/rag` | `rag:query` | 提问、范围选择、答案、citation |
| 导出任务 | `/meetings/:meetingId/exports` | `export:read` / `export:create` | Markdown / DOCX / PDF、异步状态、短链撤销 |
| 系统设置 | `/settings` | 登录用户 | 基础配置、个人信息、租户上下文 |
| 删除任务 | `/admin/deletion-jobs` | `compliance:delete` | 创建、查看、失败项、证书入口 |
| 删除证书 | `/admin/deletion-jobs/:jobId/certificate` 或 `/admin/deletion-certificates` | `compliance:delete` | 查看删除证明、hash 清单、审计摘要、下载受控副本 |
| legal hold | `/admin/legal-holds` | `compliance:legal-hold` | 创建、释放、原因、审批人 |
| break-glass | `/admin/break-glass` | `security:break-glass` | 申请、审批、拒绝、审计 |

会议详情可作为 `/meetings/:meetingId` 的聚合入口，但不替代上述独立页面能力。

一期不提供“全量 rebuild 成功会议”的前端入口；`SUCCEEDED -> PROCESSING` 仅供后端 internal-only 运维流程触发。前端只暴露已有的局部 regenerate / reindex 能力，并以后端返回状态控制按钮可见性。

## 4. 交互状态

### 4.1 任务进度

任务进度必须按 step 展示，不只展示一个线性百分比。

必须展示字段：

1. `taskStatus`: `PENDING`、`QUEUED`、`RUNNING`、`ORPHANED`、`PARTIAL_SUCCEEDED`、`SUCCEEDED`、`FAILED`、`CANCEL_PENDING`、`CANCELLED`。
2. `phase`: `WORKER_DAG_RUNNING`、`WORKER_DAG_DONE`、`JAVA_LLM_RUNNING`、`TERMINAL`，用于区分 worker 阶段、等待 Java LLM 阶段和终态。
3. step 名称：`AUDIO_UPLOAD`、`AUDIO_PREPROCESS`、`ASR`、`ALIGNMENT`、`DIARIZATION`、`SPEAKER_EMBEDDING`、`SPEAKER_MATCHING`、`TRANSCRIPT_MERGE`、`SUMMARY`、`EXTRACTION`、`RAG_INDEXING`、`EXPORT`。
4. 当前 step 状态、进度、开始时间、更新时间。
5. `source`、`errorCode`、`retryable`、`attemptNo`、`maxAttempts`。
6. `eventId` 和 `sequenceNo`，用于断线恢复。
7. 可操作按钮：取消、重试。按钮是否可用以后端状态为准。

`SUMMARY` 和 `EXTRACTION` step 由 Java `meeting-api-app` 内部 `TaskStepProgressService` 推进，不来自 `ai-worker` callback。前端展示这些 step 时必须允许 `attemptNo`、`leaseOwner`、`workerId` 为空，但 `source` 必须为 `JAVA_TASK_SERVICE`；worker callback step 的 `source` 必须为 `AI_WORKER_CALLBACK`。`retryable` 默认以后端返回为准；Java 推进 step 失败时，前端不展示 worker 重试入口，但 `SUMMARY` / `EXTRACTION` 可以通过后端提供的 regenerate action 单独触发再生成。两类 step 的状态变化仍通过 `TASK_STEP_UPDATED` SSE 可见。

SSE 断线后，前端携带 `Last-Event-Id` 尝试续接；服务端无法续接时以前端收到的 task 快照为准。重连失败时回退轮询 `GET /api/processing-tasks/{taskId}`。

### 4.2 STALE 提示

以下入口必须可见 STALE 状态：

1. 纪要页。
2. 待办、决策、风险页。
3. RAG 问答页。
4. 导出入口。

展示规则：

1. `staleStatus=ACTIVE` 时正常展示。
2. `STALE` 时展示上游内容已变更，并提供重生成入口。
3. `REBUILD_QUEUED` / `REBUILDING` / `VALIDATING` 时展示重建中，不允许用户误以为内容已最新。
4. `FAILED` 时展示失败错误码和重试入口。
5. `DELETED` 不参与 RAG，也不允许导出。

RAG 答案必须展示 `coverage` 标签：

1. `TRANSCRIPT_ONLY`：仅转录 chunk 可检索，纪要、待办、决策、风险尚未纳入答案范围。
2. `FULL`：转录、纪要和结构化事项均已进入可检索范围。
3. `coverage` 从 `TRANSCRIPT_ONLY` 变为 `FULL` 时，旧 RAG answer cache 必须失效。

前端以 RAG answer DTO 中的 `coverage` 作为唯一事实来源，不依赖单独 SSE 事件推断覆盖范围变化；coverage 变化由后端在生成 answer 时给出，前端在接收新 answer 后按 cache key 失效旧答案。

### 4.3 Citation

会议 citation 点击后应定位到对应转录 segment 和音频时间点。需要处理下列退化场景：

1. 音频已归档或下载权限失效：展示引用文本，不自动播放。
2. 权限已撤销：隐藏原文内容，展示权限提示。
3. segment 被拆分或合并：以后端返回的当前引用映射为准。
4. `timestampPrecision` 从 `WORD` 降级到 `SEGMENT`：定位到 segment 起点。

文档 citation 点击后应定位到 document、chunk、页码或段落标识。如果页码缺失，展示段落标识和文本快照。

## 5. API 对接

`meeting-web` 统一通过服务层访问 API。所有请求必须带：

事实来源：Public API path、request / response schema、SSE event schema、枚举和错误码以 `packages/meeting-contracts/openapi/public-api.yaml`、`packages/meeting-contracts/schemas/common/enums.yaml`、`packages/meeting-contracts/schemas/common/error-codes.yaml` 为准。本 SPEC 只描述前端如何消费这些契约。

```http
Authorization: Bearer <access_token>
X-Request-Id: <request_id>
X-Trace-Id: <trace_id>
Content-Type: application/json
Accept: application/json
```

响应必须按统一信封处理：

1. `success=true` 使用 `data`。
2. `success=false` 使用 `error.code`、`error.message`、`error.retryable` 和 `error.details`。
3. 页面提示优先使用稳定错误码映射，服务端 message 可作为兜底。
4. `SECURITY_LEVEL_BLOCKED` 必须展示固定业务提示。

### 5.1 页面最小 API 清单

| 页面 | 初始加载 API | 写操作 API | 必须处理的空态 / 错误态 |
|---|---|---|---|
| 登录页 | 无 | `POST /api/auth/login` | 账号锁定、密码错误、服务不可用 |
| 会议列表 | `GET /api/meetings` | 无 | 无会议、无权限、筛选无结果 |
| 会议创建 | `GET /api/auth/me` | `POST /api/meetings` | 无创建权限、安全等级不可用、422 校验失败 |
| 音频上传 | `GET /api/meetings/{meetingId}`、`GET /api/meetings/{meetingId}/files/audio/uploads/{uploadId}` | upload session、part、complete、abort | 文件过大、格式不支持、part 重试耗尽 |
| 任务进度 | `GET /api/processing-tasks/{taskId}`、SSE `/events` | retry、cancel | SSE 断线、任务失败、旧事件窗口过期 |
| 转录编辑 | `GET /api/meetings/{meetingId}/transcript` | `PATCH /segments/{segmentId}`、`POST /regenerate` | 无转录、版本冲突、STALE |
| speaker 确认 | transcript、speaker candidates | confirm、reject | 无候选、候选过期、权限不足 |
| 纪要 / 事项 | minutes、action-items、decisions、risks | regenerate、accept、reject、patch | 无纪要、LLM 阻断、STALE |
| 文档知识库 | `GET /api/documents` | upload、delete、reindex | 解析失败、扫描 PDF 不支持、legal hold 阻断 |
| RAG 问答 | scope 所需 meetings/documents | `POST /api/rag/query` | 无可检索内容、`coverage=TRANSCRIPT_ONLY`、无 citation、429 限流 |
| 导出任务 | `GET /api/meetings/{meetingId}/exports` | create、cancel、revoke-link | 内容 STALE、转换失败、短链已撤销 |
| 合规管理 | legal holds、deletion jobs、break-glass | create/release/delete/approve/reject | legal hold 阻断、审批过期、权限不足 |

权限隐藏规则：页面可以按 `GET /api/auth/me` 返回的 permissions 隐藏入口，但不得把隐藏当作安全边界；所有写操作失败必须展示服务端稳定错误码映射。

### 5.2 API client 架构

```text
apiClient
  -> auth interceptor: access token 注入、401 refresh、refresh 失败退出登录
  -> trace interceptor: 生成 X-Request-Id / X-Trace-Id
  -> idempotency interceptor: 为写操作生成或复用 Idempotency-Key
  -> error mapper: ErrorInfo.code -> i18n message / retry action
  -> retry policy: 只对网络错误、429、503 和显式 retryable 错误重试

sseClient
  -> Last-Event-Id 续接
  -> 无法续接时拉取 task snapshot
  -> 三次重连失败后降级轮询

uploadClient
  -> create session
  -> part queue 并发数默认 3，配置范围 1-5
  -> 单 part 重试 <= 3
  -> complete 前校验 fileSha256
```

Token 与 CSRF：

1. access token 只保存在内存状态中，用于注入 `Authorization: Bearer <token>`；不得写入 `localStorage`、`sessionStorage` 或可被脚本长期读取的持久化存储。
2. refresh token 如启用，必须由后端设置 `HttpOnly`、`Secure`、`SameSite=Lax` 或更严格 cookie；前端不可读取 refresh token 明文。
3. 使用 refresh cookie 的 endpoint 必须启用 CSRF 防护：后端下发非 HttpOnly CSRF token，前端通过 `X-CSRF-Token` 回传；跨站请求失败时 fail closed。
4. 401 refresh 只能单飞合并，避免多个请求并发刷新导致 token 覆盖；refresh 失败必须清空内存 token 并回到登录页。
5. 所有写操作由 idempotency interceptor 注入 `Idempotency-Key`；同一次用户动作重试必须复用原 key。

## 6. 状态管理

一期使用 TanStack Query / React Query 管理服务端状态，使用 Zustand 管理少量跨页面 UI 状态（侧边栏、上传队列草稿、RAG 当前会话草稿）。不使用 Redux 作为默认方案，除非后续出现复杂离线编辑或跨 tab 协同需求。

缓存失效规则：

1. 创建 / 修改会议后 invalidate `meetings`、`meeting:{id}`。
2. 上传完成或创建任务后 invalidate `meeting:{id}`、`tasks:{meetingId}`。
3. SSE 收到 `TASK_STEP_UPDATED` 只更新 task query cache；收到 `TRANSCRIPT_READY` invalidate transcript、minutes、rag scope。
4. 转录编辑成功后 invalidate transcript、minutes、action-items、decisions、risks、rag answers、exports。
5. speaker confirm / reject 成功后 invalidate transcript、speakers、rag answers。
6. legal hold、deletion job、break-glass 变更后 invalidate 对应 admin list 和 audit。
7. RAG answer cache key 必须包含 `coverage`；coverage 变化后旧答案失效。

离线行为：

1. 网络断开时禁止提交会改变服务端事实的编辑、确认、删除、重生成和导出创建操作。
2. 转录编辑框允许保留未提交草稿到内存或 session-scoped store，但不得标记为已保存；恢复网络后必须重新拉取当前版本并做版本冲突校验。
3. 上传中断时保留 upload session、已完成 part 和本地 hash 进度；超过 upload session TTL 后必须重新创建 session。
4. SSE 断开时先重连，再降级轮询；轮询结果仍以后端 snapshot 为准。

## 7. 安全与前端数据边界

浏览器端不得成为敏感事实或权限判断来源，所有安全结论以后端返回为准。

1. CSP：生产默认 `default-src 'self'`；`connect-src` 只允许 `meeting-api`、SSE endpoint 和必要监控域；禁止 `unsafe-inline`，如确需内联样式必须使用 nonce / hash。
2. Frame 防护：部署层必须设置 `X-Frame-Options: DENY` 或等价 `frame-ancestors 'none'`，除非后续明确支持可信内嵌。
3. Markdown / RAG answer 渲染：纪要、RAG 答案、文档片段和 evidence 文本必须经过 sanitizer 后再渲染；允许标签采用白名单，禁止脚本、事件属性、`javascript:` URL 和不可信 iframe。
4. XSS 输入清洗：用户可编辑的会议标题、转录文本、speaker 显示名、文档标题在展示层统一 escape；富文本能力一期不开放。
5. 下载与外链：导出下载只使用后端签名 URL，不在前端拼 object key；外链点击必须使用 `rel="noopener noreferrer"`。
6. 敏感字段：声纹 embedding、模型原始输出、内部 artifact body、HMAC secret、KMS key material 不得出现在前端 DTO、日志、监控 breadcrumb 或错误详情中。
7. 错误展示：服务端 `error.details` 只用于受控字段展示；未知字段不得直接 JSON dump 到页面。
8. 监控：前端错误监控默认只采集 route、error code、requestId、traceId 和浏览器环境；不得采集 transcript 正文、RAG answer、audio filename 原文或 token。

## 8. 表单、a11y、i18n 与性能

表单：使用 `react-hook-form + zod`；zod schema 名称与 OpenAPI request schema 对齐；错误消息通过稳定错误码和字段路径映射，不在组件里硬编码大段校验文案。

a11y：一期目标 WCAG 2.1 AA。所有可点击控件必须键盘可达；任务进度、上传进度和导出状态用 `aria-live="polite"`；错误提示用 `aria-live="assertive"`；citation 定位后焦点移动到对应 segment。

i18n：一期 UI 文案只交付 `zh-CN`，但错误码、枚举展示名和日期格式通过集中 dictionary 管理，避免直接散落在组件中。

性能预算：

1. 首屏 JavaScript gzip 目标 `< 200KB`，超出必须解释并拆包。
2. 首次可交互 TTI 目标 `< 3s`（局域网 / 普通办公电脑）。
3. 按 feature route code split；RAG、转录编辑、导出管理和合规管理独立 chunk。
4. 转录 segment 列表必须虚拟滚动，不能一次渲染数千 DOM 节点。
5. 大文件上传不得把整文件读入内存，仅分片 hash / 上传。
6. 转录分页默认首屏拉取 `200` 个 segment 或当前播放时间附近窗口；继续滚动按 cursor 懒加载，每页上限 `500` 个 segment。
7. RAG answer、纪要 Markdown 和长文档预览必须按内容区域懒加载或虚拟化，避免一次性渲染超长 Markdown。

## 9. 功能分包建议

```text
src/
  app/                  Router、全局 Provider、鉴权守卫
  features/
    meetings/           会议列表、创建、详情和音频上传页面
    transcript/         转录查看、编辑和 citation 定位
    speakers/           speaker 候选确认和声纹档案视图
    minutes/            纪要、待办、决策、风险
    rag/                RAG 问答
    exports/            导出任务
    auth/               后续登录页和登录态视图
    knowledge/          后续文档知识库
    compliance/         legal hold / deletion job / deletion certificate
    admin/              后续 break-glass
  services/             API client、SSE client、上传 client
  shared/
    components/
    domain/
    hooks/
    utils/
  styles/
```

约束：

1. API DTO 类型从 `meeting-contracts` 生成或手写同步，不在页面内重复定义大型结构。
2. 状态机、错误码、枚举放在 shared domain 或生成类型中。
3. 上传、SSE、RAG 对话等长流程封装为 feature service，不直接散落在页面组件中。
4. 页面入口优先放在 `features/<domain>/pages/` 或由 `app/router.tsx` 集中映射，不再单独要求顶层 `pages/` 目录。

## 10. 测试与验收标准

### 10.1 测试层级

1. Unit：Vitest，覆盖 error mapper、idempotency key、SSE event reducer、STALE 状态展示规则。
2. Component：React Testing Library，覆盖登录、上传状态、任务进度、转录编辑、RAG citation、导出状态。
3. API mock：Mock Service Worker，mock 统一响应信封、错误码、SSE 事件流。
4. E2E：Playwright，覆盖登录 -> 创建会议 -> 上传 -> 任务进度 -> 转录 -> 纪要 -> RAG -> 导出的主链路，以及 `SECURITY_LEVEL_BLOCKED` 分支。
5. 安全：覆盖 Markdown sanitizer、RAG answer XSS、token 不落持久化存储、CSRF header 缺失失败、错误详情不直出。
6. 监控：接入 Sentry 或等价自研前端监控；验收环境必须能通过 `requestId` / `traceId` 关联到后端日志，且敏感正文不会进入事件 payload。

### 10.2 验收标准

1. 用户可以登录、创建会议、上传 4 小时以内音频并看到任务进入队列。
2. 任务进度页面能展示 step 级状态，SSE 断线可恢复或回退轮询。
3. 转录页面能展示 segment，支持编辑文本和确认 speaker。
4. speaker 候选展示置信度，支持确认和拒绝。
5. 纪要、待办、决策、风险能展示 evidence 和 STALE 状态。
6. RAG 答案包含 `coverage` 标签和 citation，citation 可定位到会议 segment 或文档 chunk。
7. 导出支持 Markdown / DOCX / PDF 异步任务和短链撤销。
8. 声纹 embedding 不出现在任何前端响应展示中。
9. `CONFIDENTIAL` / `SECRET` 自动 LLM 相关入口 fail closed，并展示一期限制提示。
10. legal hold、deletion job、deletion certificate、break-glass 管理页面具备最小可用流程。
