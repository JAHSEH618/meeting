# meeting-api-app Spec

## 1. 项目定位

`meeting-api-app` 是应用层，负责用例编排、事务边界、租户上下文、权限编排、幂等控制、状态机推进和 outbox 发布。它连接 adapter、domain 和 infrastructure 端口，但不写具体 SQL、不调用具体外部 SDK。

## 2. 包边界

建议包结构：

```text
com.meeting.api.app
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
  audit/
  common/
```

每个业务域可以包含：

```text
command/
query/
executor/
assembler/
policy/
```

## 3. 通用应用规则

1. 每个写用例定义事务边界。
2. 事务开始前设置 tenant context。
3. 权限校验在状态变更前完成。
4. 状态变更和 `domain_events_outbox` 同事务提交。
5. app 层调用 domain 聚合或领域服务执行核心规则。
6. app 层通过 domain 端口调用 Repository / Gateway。
7. app 层负责幂等键检查和重放结果返回。

## 4. 关键用例

### 4.1 会议创建

1. 校验登录态和租户。
2. 校验安全等级枚举。
3. 创建 meeting 聚合。
4. 保存参会人和初始状态。
5. 记录 audit event。

### 4.2 上传完成与处理任务创建

1. 校验会议访问权限。
2. 校验文件元信息和音频时长上限。
3. 保存 `meeting_files`。
4. 创建 `processing_tasks` 和初始 step。
5. 写 outbox 事件，异步投递 RabbitMQ。
6. 返回 task id。

### 4.3 Callback 处理

1. 校验 HMAC、timestamp、nonce。
2. 校验 idempotency key。
3. 校验 tenant、task、meeting 关系。
4. 校验 attempt 和 lease owner。
5. 校验 expected input version。
6. 根据 endpoint 推进 task step、保存 artifact、保存 transcript、保存 speaker candidates 或进入终态。
7. 对重复 callback 返回已处理结果。
8. 对幂等键相同但 payload 不一致返回 `CALLBACK_IDEMPOTENCY_CONFLICT`。

### 4.4 转录编辑

1. 校验会议访问和编辑权限。
2. 更新 segment 的 `edited_text` 和 `current_text`。
3. 保留 `original_text` 不变。
4. 增加 `transcript_version`。
5. 将纪要、待办、决策、风险和相关 RAG chunk 标记 STALE。
6. 写 outbox 事件触发可选重建。

### 4.5 纪要生成

1. 校验 security level。
2. `PUBLIC` / `INTERNAL` 允许走 DashScope。
3. `CONFIDENTIAL` / `SECRET` 返回 `SECURITY_LEVEL_BLOCKED`。
4. 组装结构化转录和必要上下文。
5. 调用 `llm-gateway` 端口。
6. 校验 JSON schema 和 evidence。
7. 生成纪要版本、AI 建议待办、决策和风险。
8. 已确认业务字段不得被重生成覆盖。

### 4.6 RAG 查询

1. 鉴权并计算 allowed scope。
2. 通过 rag repository 做 metadata filter + vector retrieval + keyword retrieval。
3. 检索结果做二次权限和 STALE 校验。
4. 组装上下文和 citation。
5. 根据安全等级调用 LLM 或 fail closed。
6. 保存 query log、LLM log 和 artifact manifest 关联。

### 4.7 导出

1. 校验会议访问和导出权限。
2. 检查内容 STALE 状态。
3. 绑定 `minutesVersion`、`transcriptVersion`、`ragVersion`。
4. 创建 `export_jobs`。
5. 投递 `export-queue` 或调用 export gateway。
6. 文件写入 TOS 后更新状态。
7. 支持取消和短链撤销。

### 4.8 legal hold 与删除

1. 创建 legal hold 时要求 reason、审批人和范围。
2. deletion job 创建前检查 legal hold。
3. 删除完成后生成 deletion certificate。
4. 删除、保全和解除都写 audit event。

## 5. 状态机

app 层负责调用 domain 状态机，禁止 adapter 或 infrastructure 直接改状态。

关键状态：

1. `processing_tasks.status`。
2. `processing_task_steps.status`。
3. 纪要、事项和 chunk 的 `stale_status`。
4. `export_jobs.status`。
5. `deletion_jobs.status`。

## 6. 验收标准

1. 所有写用例有清晰事务边界。
2. tenant context 缺失时 fail closed。
3. callback 幂等重放不产生重复 segment。
4. 旧 attempt callback 不能覆盖新 attempt。
5. 转录编辑触发下游 STALE。
6. outbox 与业务数据同事务提交。
7. CONFIDENTIAL / SECRET 自动 LLM 被阻断。
8. RAG 查询经过权限过滤和二次校验。
