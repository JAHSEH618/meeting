# meeting-web Spec

## 1. 工程定位

`meeting-web` 是本地会议智能系统的一期 React SPA。它只调用 `meeting-api` 的 Public API 和 SSE，不直接访问 Python、RabbitMQ、PostgreSQL、TOS 或 DashScope。

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

| 页面 | 建议路由 | 主要能力 |
|---|---|---|
| 登录页 | `/login` | 登录、错误提示、登录态初始化 |
| 会议列表 | `/meetings` | 列表、搜索、状态筛选、安全等级筛选 |
| 会议创建 | `/meetings/new` | 创建会议、选择安全等级、参会人 |
| 会议详情 | `/meetings/:meetingId` | 基本信息、音频、任务、纪要、RAG 入口 |
| 音频上传 | `/meetings/:meetingId/audio` | 断点续传、上传进度、取消、重试 |
| 任务进度 | `/meetings/:meetingId/tasks/:taskId` | SSE 步骤级进度、错误码、重试、取消 |
| 转录编辑 | `/meetings/:meetingId/transcript` | segment 列表、编辑、版本和 STALE 提示 |
| speaker 确认 | `/meetings/:meetingId/speakers` | 匿名 label、候选人、置信度、确认和拒绝 |
| 声纹档案 | `/speaker-profiles` | 档案、授权、参考音频、撤销、删除 |
| 纪要 | `/meetings/:meetingId/minutes` | 纪要章节、重生成、diff 或新建议 |
| 待办/决策/风险 | `/meetings/:meetingId/items` | AI 建议、接受、拒绝、状态和 evidence |
| 文档知识库 | `/documents` | 上传、解析状态、reindex、删除 |
| RAG 问答 | `/rag` | 提问、范围选择、答案、citation |
| 导出任务 | `/meetings/:meetingId/exports` | Markdown / DOCX / PDF、异步状态、短链撤销 |
| 系统设置 | `/settings` | 基础配置、个人信息、租户上下文 |
| 删除任务 | `/admin/deletion-jobs` | 创建、查看、失败项、证书入口 |
| legal hold | `/admin/legal-holds` | 创建、释放、原因、审批人 |
| break-glass | `/admin/break-glass` | 申请、审批、拒绝、审计 |

## 4. 交互状态

### 4.1 任务进度

任务进度必须按 step 展示，不只展示一个线性百分比。

必须展示字段：

1. `taskStatus`: `PENDING`、`QUEUED`、`RUNNING`、`ORPHANED`、`PARTIAL_SUCCEEDED`、`SUCCEEDED`、`FAILED`、`CANCEL_PENDING`、`CANCELLED`。
2. step 名称：`AUDIO_UPLOAD`、`AUDIO_PREPROCESS`、`ASR`、`ALIGNMENT`、`DIARIZATION`、`SPEAKER_EMBEDDING`、`SPEAKER_MATCHING`、`TRANSCRIPT_MERGE`、`SUMMARY`、`EXTRACTION`、`RAG_INDEXING`、`EXPORT`。
3. 当前 step 状态、进度、开始时间、更新时间。
4. `errorCode`、`retryable`、`attemptNo`、`maxAttempts`。
5. 可操作按钮：取消、重试。按钮是否可用以后端状态为准。

SSE 断线后，前端先尝试重连；重连失败时回退轮询 `GET /api/processing-tasks/{taskId}`。

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

### 4.3 Citation

会议 citation 点击后应定位到对应转录 segment 和音频时间点。需要处理下列退化场景：

1. 音频已归档或下载权限失效：展示引用文本，不自动播放。
2. 权限已撤销：隐藏原文内容，展示权限提示。
3. segment 被拆分或合并：以后端返回的当前引用映射为准。
4. `timestampPrecision` 从 `WORD` 降级到 `SEGMENT`：定位到 segment 起点。

文档 citation 点击后应定位到 document、chunk、页码或段落标识。如果页码缺失，展示段落标识和文本快照。

## 5. API 对接

`meeting-web` 统一通过服务层访问 API。所有请求必须带：

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

## 6. 功能分包建议

```text
src/
  app/                  Router、全局 Provider、鉴权守卫
  pages/                页面入口
  features/
    auth/
    meetings/
    uploads/
    processing-tasks/
    transcript/
    speakers/
    minutes/
    knowledge/
    rag/
    exports/
    compliance/
    admin/
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

## 7. 验收标准

1. 用户可以登录、创建会议、上传 4 小时以内音频并看到任务进入队列。
2. 任务进度页面能展示 step 级状态，SSE 断线可恢复或回退轮询。
3. 转录页面能展示 segment，支持编辑文本和确认 speaker。
4. speaker 候选展示置信度，支持确认和拒绝。
5. 纪要、待办、决策、风险能展示 evidence 和 STALE 状态。
6. RAG 答案包含 citation，citation 可定位到会议 segment 或文档 chunk。
7. 导出支持 Markdown / DOCX / PDF 异步任务和短链撤销。
8. 声纹 embedding 不出现在任何前端响应展示中。
9. `CONFIDENTIAL` / `SECRET` 自动 LLM 相关入口 fail closed，并展示一期限制提示。
10. legal hold、deletion job、deletion certificate、break-glass 管理页面具备最小可用流程。
