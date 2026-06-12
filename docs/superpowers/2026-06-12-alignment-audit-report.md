# 代码/业务逻辑对齐审计报告（2026-06-12）

> 目标：保证代码逻辑与业务逻辑贴合 README.md 与 docs/structure.md 的要求。
> 约束（用户指令）：**会议不分级、无封控（LLM 出网阻断门）— Phase K 已移除，不得回加**；legal hold / break-glass / deletion jobs 是独立合规功能，**保留**。
> 基线验证（修复前）：Java `mvnw test` 569 ✅ · Python pytest 241 ✅ / **pyright ❌ 2 errors** · meeting-web **tsc ❌ 4 errors + vitest ❌ 4 failures** · ai-worker-web ✅ 92/92 · contracts `npm run check` ✅（JDK 17 下）

## A. Phase K 残留（SecurityLevel / 安全门）

### A1. 阻断 CI / 运行时（必须修）

| # | 位置 | 问题 |
|---|---|---|
| 1 | `packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json:10,35` | `securityLevel` 仍为 **required** 字段 + 枚举属性 — 契约未清理完 |
| 2 | `apps/ai-worker/ai_worker/schemas/rabbitmq/processing-task-message.schema.json` | 同上（worker 端 fail-fast 校验副本） |
| 3 | `meeting-api-app/.../task/ProcessingTaskApplicationService.java:366,423,464` | 三处硬编码 `securityLevel: "INTERNAL"` 以满足 #1 的 schema |
| 4 | `db/migration/V202605110001__initial_schema.sql` | `documents` / `knowledge_chunks` / `llm_call_logs` / `llm_data_boundary_logs` 仍有 `security_level` 列 + `security_level` 枚举类型；只有 `meetings` 在 V202606110001 被 drop |
| 5 | `JdbcLlmCallLogRepository.record()` | **运行时 bug**：INSERT 不含 `security_level`，但 `llm_call_logs.security_level NOT NULL` 无默认值 → 每次真实 LLM 调用日志落库都会违反 NOT NULL（单测用 fake 才没暴露） |
| 6 | 6 个 `*IT.java`（MeetingFinalizeFlowIT / JdbcProcessingTaskRepositoryIT / PostgreSqlBaselineIT / JdbcCallbackEventRepositoryIT / JdbcKnowledgeChunkRepositoryIT / JdbcExportJobRepositoryIT） | IT 走全量 Flyway（含 drop），仍 `INSERT INTO meetings (..., security_level, ...)` → `mvnw verify`（CI）必挂 |
| 7 | `meeting-web src/shared/api/__tests__/types-consistency.test.ts:46,114` | 仍断言 enums.yaml 有 securityLevel、Meeting schema 有 `$ref SecurityLevel` → vitest ×2 失败 |
| 8 | `meeting-web MeetingListPage.test.tsx:16` / `DocumentsPage.test.tsx`（badges 用例） | 仍断言 `INTERNAL` 徽章 → vitest ×2 失败 |
| 9 | `meeting-web` 4 个测试文件 `getAllByRole(...)[n]` 索引 | `noUncheckedIndexedAccess` 下 tsc ×4 错误（BreakGlassPage:94 / DeletionJobsPage:70 / DocumentsPage:90 / ExportsPage:127）— CI 门 |
| 10 | `apps/ai-worker` pyright ×2（与 Phase K 无关但同为 CI 门） | `cam_plus_plus_runtime.py:107` modelscope import 不可解析；`:154` `AudioMetadata.sample_rate` 属性不存在 |

### A2. 死代码 / 死配置（清理）

| # | 位置 | 问题 |
|---|---|---|
| 11 | `error-codes.yaml:275` + Java `ErrorCode.SECURITY_LEVEL_BLOCKED` + `meeting-web error-mapper.ts:40` + `minutes/queries.ts:12` + `MinutesPage.test.tsx` ×2 用例 | 无任何抛出方的死错误码链 |
| 12 | `MeetingApiMetrics.llmBlockedBySecurityLevelCounter`（无调用方）+ `infra prometheus rules.yaml:133 LlmBlockedBySecurityLevelSurge` 告警 | 死指标 + 永不触发的告警 |
| 13 | `ProdProfileValidator` `meeting.llm.allow-confidential`（:47,:116）+ `application.yml:128` + `ProdProfileValidatorTest:90` | 安全门残留配置面 |
| 14 | `prompts/meeting_minutes_zh/v0.1.0.json` 模板 `- 安全等级：{{securityLevel}}` | 变量已无人提供 → 渲染成空标签进入 DashScope prompt |
| 15 | `RagAuthorizationService.java:123` 日志字段 `clearedBySecurity` + `RagQueryApplicationService.java:63` javadoc "clearance" | 误导性命名（实际无安全过滤） |
| 16 | `OutboxPublisherRoutingTest:132,179` payload fixture 带 securityLevel | 改 schema 后需同步 |
| 17 | ai-worker `domain/task/models.py:23 security_level` + `task_consumer.py:50` + 7 个测试文件 fixture | 传递但从未使用 |
| 18 | contracts `fixtures/valid+invalid/processing-task-*.json` 7 个 + `public-api-create-meeting-200.json` | fixture 带 securityLevel |
| 19 | **ai-worker-web 整页残留 UI**：`types.ts:8,69,86,96,105`、`endpoints.ts:19,75,222`、`NewMeetingPage.tsx`（安全级别下拉，提交 securityLevel — Java 端 Jackson 静默丢弃）、`MeetingsPage.tsx:74-88`（安全等级列，渲染 undefined）、`MeetingDetailPage.tsx:100,250`（SECURITY_LEVEL_BLOCKED 横幅）+ 对应测试/e2e | Phase K 只清了 meeting-web，工作站 SPA 全套未清 |
| 20 | `meeting-web e2e/tests/main-flow.spec.ts:50,76-79` + `e2e/README.md` | 仍操作不存在的安全等级下拉 + CONFIDENTIAL 分支 |
| 21 | `infra scripts legal-hold-lifecycle-smoke.sh:66` / `export-pdf-smoke.sh:54` | 创建会议 payload 带 securityLevel（Java 忽略，但应清） |
| 22 | `apps/ai-worker/tests/admin/test_meeting_orchestration.py` 等 mock Java 响应带 securityLevel | stale mock |

### A3. 文档漂移（与代码对齐）

| 文件 | 位置 | 问题 |
|---|---|---|
| `README.md` | ~:71 mermaid「受安全等级控制」、~:187「安全等级控制」、:359 约束 #3 整条、:387「一期范围…」legal hold 保留但「CONFIDENTIAL/SECRET 自动 LLM」需改写、:3 状态行仍写「剩 Phase J 验收」 | 更新为无分级现实 + v1.1.0 状态 |
| `CLAUDE.md`（根） | 不变量 #2「Security level gates LLM egress」、MVP-2 描述 | 改写（保留「音频/声纹不出网」部分 — 仍正确） |
| `docs/structure.md` | 要点 9「LocalLLM 用于后续 CONFIDENTIAL/SECRET 自动 LLM」 | 删改 |
| `todo.md` | 末行「保持 PUBLIC/INTERNAL 可自动 LLM…fail closed」、阶段 8「CONFIDENTIAL/SECRET 误允许 LLM」 | 改写/标注 Phase K 移除 |
| SPEC 文件 | `meeting-api/SPEC.md:13,138,249,272-323`、`meeting-api-app/SPEC.md:69,112,143`、`meeting-api-domain/SPEC.md:39,153`、`meeting-api-client/SPEC.md:25,80`、`meeting-web/SPEC.md:23,50,51,147,155,292` | 各处安全等级章节/行 |
| `docs/spec-clarifications.md` | 缺 Phase K 勘误条目 | 新增（不重写 spec.md） |

## B. 架构对齐核查（README/structure 主张 vs 代码）

| 主张 | 结论 |
|---|---|
| COLA 分层 + ArchUnit 门禁 | ✅（`ArchitectureBoundaryTest` 在 569 个通过用例中） |
| 7 个队列（audio-cpu/gpu-asr/gpu-diar/gpu-speaker/embed/llm/export + DLQ） | ✅ `rabbitmq/definitions.json` 完全一致 |
| `pipelineSteps` 禁含 Java-owned 步骤 | ✅ schema enum 只有 8 个 worker 步骤 |
| export-queue 由 Java 消费 | ✅ `ExportQueueConsumer`（infrastructure） |
| WORKER_PHASE_COMPLETED → Java 驱动 SUMMARY/EXTRACTION | ✅ `ProcessingTaskApplicationService` 监听 |
| 双 HMAC 密钥 + 第三把 admin JWT | ✅ `ProdProfileValidator:42-44` + `jwt_middleware.py` |
| BFF 薄透传、Java 唯一写者/权限方 | ✅ `admin/meetings.py` 全部 passthrough |
| legal hold / break-glass / deletion / audit 存在且接线 | ✅（控制器+服务+Runner+页面；`MeetingApplicationService.delete` 先查 LegalHoldCheckPort — 测试日志可证） |
| Outbox / RLS / 心跳通道 / lease | ✅（由 569 个单测覆盖；OutboxPublisher schema 校验日志可证） |
| llm_data_boundary_logs | ⚠️ 无任何写入方 — 死表（随残留清理一并 drop 列；表保留与否见迁移） |

## C. 修复方案（按依赖序）

1. **contracts 先行**：schema 删 securityLevel（required+property）、error-codes.yaml 删 SECURITY_LEVEL_BLOCKED、fixtures 清理 → `npm run check` + `npm run codegen`（JDK 17）。
2. **新 Flyway 迁移** `V202606120830__remove_security_level_remnants.sql`：drop `documents/knowledge_chunks/llm_call_logs/llm_data_boundary_logs` 的 `security_level` 列 + `DROP TYPE security_level`（修复 A1#5 运行时 bug）。
3. **Java**：payload builders ×3、ErrorCode、Metrics、ProdProfileValidator+yml、模板 JSON、日志/javadoc 措辞、IT INSERT ×6、PostgreSqlBaselineIT pg_type 断言、OutboxPublisherRoutingTest、ProdProfileValidatorTest。
4. **Python**：schema 副本、models/task_consumer、测试 fixture、pyright ×2。
5. **meeting-web**：types-consistency 改为断言"无 securityLevel"、两页 badge 断言、Minutes 死码用例、error-mapper、queries.ts、tsc ×4、e2e spec+README。
6. **ai-worker-web**：全套 securityLevel UI/类型/测试/e2e 移除（A2#19）。
7. **infra**：prometheus 告警、两个 smoke 脚本。
8. **docs**：A3 全部。

修复后门禁：5 个工作区全套测试 + `npm run check` + `codegen` 干净 diff + `psql ON_ERROR_STOP` 验新迁移。
