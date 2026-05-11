# meeting-api-adapter Spec

## 1. 项目定位

`meeting-api-adapter` 是入站适配层，负责 REST Controller、SSE、internal callback、BFF 响应适配和消息入口适配。它把外部协议转换为 `meeting-api-app` 的命令和查询，不直接写数据库，不实现领域规则。

## 2. 包边界

建议包结构：

```text
com.meeting.api.adapter
  auth/
  user/
  meeting/
  storage/
  task/
  transcript/
  speaker/
  minutes/
  document/
  rag/
  export/
  compliance/
  internal/
  sse/
  common/
```

约束：

1. Controller 只做协议解析、参数校验、鉴权入口、调用 app service 和响应转换。
2. 业务状态变更由 app 层完成。
3. 所有响应使用 `ApiResponse`。
4. 所有异常转换为稳定 `ErrorCode`。
5. internal callback 与 public API 分包隔离。

## 3. Public REST API

需要实现 `apps/meeting-api/SPEC.md` 中列出的 `/api` endpoint。

通用要求：

1. 读取登录态并传入 app 层上下文。
2. 生成或透传 `X-Request-Id`、`X-Trace-Id`。
3. 校验请求体基础格式。
4. 不在 Controller 中做权限决策，权限编排交给 app 层。
5. 分页统一 cursor 响应。
6. 写操作返回可跟踪的业务 id 和任务 id。

## 4. SSE

Endpoint：

```http
GET /api/processing-tasks/{taskId}/events
```

要求：

1. 建连时鉴权并校验 task 访问权限。
2. 发送当前 task 快照，避免前端错过历史状态。
3. 后续推送 step 变更、终态、错误码和可重试状态。
4. SSE 断线后允许前端使用 last event id 或回退轮询。
5. 不推送跨租户数据。

## 5. Internal Callback API

Endpoint：

```http
PATCH /internal/processing-tasks/{taskId}/steps/{stepName}
POST  /internal/processing-tasks/{taskId}/artifacts
POST  /internal/processing-tasks/{taskId}/transcript
POST  /internal/processing-tasks/{taskId}/speaker-candidates
POST  /internal/processing-tasks/{taskId}/complete
POST  /internal/processing-tasks/{taskId}/fail
```

Adapter 层职责：

1. 读取 callback headers。
2. 验证必填 header 存在。
3. 将 header 和 body 转换为 app command。
4. 调用 app 层进行 HMAC、nonce、幂等、attempt、lease 和业务关系校验。
5. 返回统一响应。

不得在 Controller 中直接落库 callback 结果。

## 6. BFF 响应适配

前端页面可能需要聚合视图，例如会议详情需要会议基本信息、最新任务、STALE 摘要、导出摘要。BFF 适配规则：

1. 聚合查询可以放在 adapter 的 BFF Controller，但实际数据读取仍调用 app query service。
2. 聚合不改变业务事实。
3. BFF DTO 与领域对象隔离。
4. 大文本按需懒加载，避免会议详情一次返回完整转录。

## 7. 验收标准

1. `/api/meetings` 最小链路可用。
2. `/internal` callback 占位演进为真实 header 校验和 app command 调用。
3. SSE 能推送任务 step 级状态。
4. Controller 无数据库访问代码。
5. 统一异常映射到 `ErrorCode` 和 `ApiResponse`。
6. Public API 与 internal callback API 分包清晰。
