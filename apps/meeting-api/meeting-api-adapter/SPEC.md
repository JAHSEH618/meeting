# meeting-api-adapter Spec

## 1. 项目定位

`meeting-api-adapter` 是协议适配层，负责 REST Controller、SSE、internal callback、BFF 响应适配和一期唯一的 MQ 入站场景 `export-queue` consumer。它把外部协议转换为 `meeting-api-app` 的命令和查询，不直接写数据库，不实现领域规则。

## 2. 包边界

目标包结构（新增入口必须按业务域落包；MVP-0 至少覆盖 `meeting`、`internal`、`sse`、`common`，`export/queue` 在导出切片开始前补齐）：

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
    queue/
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
6. RabbitMQ 入站只允许 `export/queue`；其它 worker 任务消费不放在 adapter。已有临时 `mq` 占位不得承载新的通用 MQ inbound 逻辑。

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
4. SSE 事件必须包含 `eventId` 和 `sequenceNo`。
5. SSE 断线后允许前端使用 `Last-Event-Id` 请求头续接；服务端无法续接时发送当前 task 快照并继续推新事件。
6. 不推送跨租户数据。

一期实现策略：

1. 使用 Spring MVC `SseEmitter` + Servlet async，不引入 WebFlux。
2. 默认最大 SSE 并发 `500`，超过返回 429；配置键 `sse.max-connections`。
3. 每个连接心跳间隔 `15s`，空闲超时 `120s`。
4. 事件缓存窗口默认 `30min`，缓存 key 为 `taskId`，用于 `Last-Event-Id` 续接。
5. event id 编码 `{taskId}:{sequenceNo}`，`sequenceNo` 同 task 内单调递增。
6. 后端线程池必须独立于 Tomcat request worker，避免长连接耗尽普通请求处理线程。

## 5. Internal Callback API

Endpoint：

```http
PATCH /internal/processing-tasks/{taskId}/steps/{stepName}
POST  /internal/processing-tasks/{taskId}/artifacts
POST  /internal/processing-tasks/{taskId}/transcript
POST  /internal/processing-tasks/{taskId}/speaker-candidates
POST  /internal/processing-tasks/{taskId}/embeddings
POST  /internal/processing-tasks/{taskId}/complete
POST  /internal/processing-tasks/{taskId}/fail
```

Adapter 层职责：

1. 读取 callback headers。
2. 验证必填 header 存在；`Idempotency-Key` 对所有 callback 仍是必填 header，包括 heartbeat。
3. 将 header 和 body 转换为 app command。
4. `PATCH .../steps/{stepName}` controller 在调用 app 前必须解析 body 的 `status` 和 `progress` 字段；命中 `(status=RUNNING && progress>0)` 时分发为 `StepProgressHeartbeatCommand`，其余 step update 分发为 `StepCallbackCommand`。两类 command 在 app 层走不同幂等路径。
5. 将原始 request URI path + query 传给 app 层，必须保留 `/internal` server prefix，不能传 Spring 去掉 servlet path 后的相对路径。
6. 调用 app 层进行 HMAC、nonce、幂等、attempt、lease 和业务关系校验。
7. 对 heartbeat command，adapter 不做幂等 short-circuit；只负责透传到 app，由 app 跳过 `callback_events` 并 latest-wins 更新进度。
8. 返回统一响应。

不得在 Controller 中直接落库 callback 结果。

## 6. Export Queue Consumer

一期只有 `export-queue` 由 Java 进程内消费，其它音频、ASR、Diarization、speaker、embedding、LLM 队列由 `ai-worker` 或 app / infrastructure 对应组件处理，不在 adapter 中实现通用 MQ inbound。

职责：

1. 从 `export-queue` 读取 export job message。
2. 按 `packages/meeting-contracts/schemas/rabbitmq/export-job-message.schema.json` 校验 message 的 `tenantId`、`exportId`、`meetingId`、`format`、`expectedInputVersion`、`traceId`。
3. 转换为 app 层 `RunExportJobCommand`。
4. ack / nack 由 app 层处理结果和 retry 策略决定。
5. 不直接调用 LibreOffice，不直接写 TOS，不直接改 `export_jobs`。

## 7. BFF 响应适配

前端页面可能需要聚合视图，例如会议详情需要会议基本信息、最新任务、STALE 摘要、导出摘要。BFF 适配规则：

1. 聚合查询可以放在 adapter 的 BFF Controller，但实际数据读取仍调用 app query service。
2. 聚合不改变业务事实。
3. BFF DTO 与领域对象隔离。
4. 大文本按需懒加载，避免会议详情一次返回完整转录。

## 8. 验收标准

1. `/api/meetings` 最小链路可用。
2. `/internal` callback 占位演进为真实 header 校验和 app command 调用。
3. SSE 能推送任务 step 级状态。
4. Controller 无数据库访问代码。
5. 统一异常映射到 `ErrorCode` 和 `ApiResponse`。
6. `export-queue` consumer 只做消息适配和 app command 调用。
7. Public API 与 internal callback API 分包清晰。
