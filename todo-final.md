# 会议工作站（ai-worker UI）—— 落地待办清单

> 已锁定的设计决策：**D1=B / D2=A / D3=A / D4=B / D5=A / D6=A / D7=纳入本期**。
> 本文件是执行依据，按工作区组织，逐项可勾选。每个 checkbox 完成的判定标准写在条目里（CI 通过 / 文件落地 / 测试绿）。

---

## 0. 决策锚点（不再改动，供后续条目引用）

- **D1 — 文档⇄会议关联**：新建关联表 `meeting_documents(meeting_id, document_id, role)`，文档仍为租户级，可被多场会议引用。
- **D2 — 术语库**：`meetings.glossary_terms jsonb DEFAULT '[]'`，本期不建独立表。
- **D3 — 纪要门控**：`processing_tasks.hold_at_worker_phase boolean`，task 创建带 flag；`WorkerPhaseCompletedListener` 跳过自动 begin；新增 `POST /api/processing-tasks/{id}:resume-java-phase` 由前端显式触发。
- **D4 — 纪要入 RAG**：`MinutesApplicationService.generateForTask` 完成后写 outbox `MinutesGeneratedEvent`，独立 listener `MinutesGeneratedRagIndexer` 投 `TEXT_EMBEDDING`，`rag_chunks.source_type=MINUTES`。
- **D5 — docx 下载**:`ExportJob` 完成时落 `export_short_links`，DTO 暴露 `downloadUrl`（短链 + TTL + 撤销）。
- **D6 — 上传链路**：浏览器直传 Java（多分片复用现有协议），worker 仅做 BFF 编排不中转大文件。
- **D7 — 声纹真识别**：新增 Java internal `POST /internal/speakers/reference-embeddings`（HMAC，复用 `meeting.ai-worker.hmac-secret`），worker 替换 `ReferenceEmbeddingSupplier` 生产实现。

---

## 1. 前置摸底（开工前 ≤ 半天）

- [x] **P0.1** 现状 = Apache POI XWPF 渲染（`DocxExportGateway` / `PdfExportGateway` / `MarkdownExportGateway` + `ExportGatewayRegistry` 已就绪，pom 已含 poi-ooxml）；本期改动 = 不动渲染栈，只在 `MinutesApplicationService` 注入 glossary + reference document 摘要，让纪要内容反映这些上下文。R1 已答。
- [x] **P0.2** Stub 位置 = `apps/ai-worker/ai_worker/pipeline/speaker/matcher.py:114` 仅定义 `ReferenceEmbeddingSupplier` Protocol；唯一引用点 = `AuthorizedScopeMatcher.__init__` 的 `reference_supplier` 形参（line 70-78）。无现存 production impl，D7 接入新 client 后注入此处。HMAC 样板 = `ai_worker/infrastructure/internal_api/auth.py`（inbound）+ `ai_worker/infrastructure/java_callback/client.py`（outbound）。
- [x] **P0.3** 基线全绿：`npm run check` ✅ / `./mvnw test -DskipITs` ✅ / `uv run pytest -q` ✅ / `npm test` (web) ✅。

---

## 2. 契约 `packages/meeting-contracts/`

### 2.A `openapi/public-api.yaml`
- [x] A1.1 新增 `POST /api/meetings/{meetingId}/documents`（attach 文档，请求体 `{documentId, role: REFERENCE}`）
- [x] A1.2 新增 `DELETE /api/meetings/{meetingId}/documents/{documentId}`
- [x] A1.3 新增 `GET /api/meetings/{meetingId}/documents`
- [x] A1.4 新增 `PATCH /api/meetings/{meetingId}/glossary`（请求体 `{terms:[{term, definition?, aliases?}]}`，覆盖式）
- [x] A1.5 新增 `GET /api/meetings/{meetingId}/glossary`
- [x] A1.6 新增 `POST /api/processing-tasks/{taskId}:resume-java-phase`（响应含新的 `phase=JAVA_LLM_RUNNING`）
- [x] A1.7 `POST /api/meetings/{meetingId}/processing-tasks` 请求体新增可选 `holdAtWorkerPhase: bool`（默认 false，向后兼容）
- [x] A1.8 `ExportJob` 响应 schema 增加 `downloadUrl: string|null`（P0.1 摸底确认已存在）

### 2.B `openapi/ai-worker-internal-api.yaml`（D7）
- [x] A2.1 新增 `POST /internal/speakers/reference-embeddings`：请求体 `{personIds: string[], tenantId, asOf?}`；响应 `{items:[{personId, values: number[], dim, hash, computedAt}]}`，HMAC 同 rerank
- [x] A2.2 在 `error-codes.yaml` 新增（若缺失）`SPEAKER_REFERENCE_UNAVAILABLE` / `SPEAKER_REFERENCE_STALE`

### 2.C `schemas/common/enums.yaml`
- [x] A3.1 新增 `documentRole: REFERENCE | ATTACHMENT`
- [x] A3.2 `processingStep` 检查：无需新增（SUMMARY/EXTRACTION 已存在）
- [x] A3.3 `ragSourceType` 已存在则加 `MINUTES`（`sourceType` 含 MINUTES + callback / worker-internal 同步）

### 2.D `schemas/rabbitmq/processing-task-message.schema.json`
- [x] A4.1 新增可选字段 `glossaryTerms: string[]`（worker 未实现 hot-word bias 时忽略，不报错）
- [x] A4.2 新增可选字段 `referenceDocumentIds: string[]`
- [x] A4.3 新增可选字段 `controlFlags.holdAtWorkerPhase: boolean`（worker 不感知，仅 Java 侧路由用）

### 2.E 校验门
- [x] A5.1 `npm run check` 通过（Spectral + JSON Schema + enum 一致性 + `pipelineSteps` 守卫）
- [x] A5.2 `npm run codegen` 后 `git diff` 干净；TS / Python / Java 三端生成产物全部对齐
- [x] A5.3 `npm run codegen:check-temp` 0 diff（check 等价覆盖）

---

## 3. Java meeting-api（`apps/meeting-api`）

### 3.A Flyway 迁移（`meeting-api-infrastructure/.../db/migration/`）
- [x] B1.1 `V202605190001__meeting_documents.sql`：建表 `meeting_documents(...)`、唯一约束 `(meeting_id, document_id) where deleted_at IS NULL`、外键、RLS、索引（攻克 D1）
- [x] B1.2 `V202605190002__meetings_glossary.sql`：`ALTER TABLE meetings ADD COLUMN glossary_terms jsonb`，GIN 索引
- [x] B1.3 `V202605190003__processing_tasks_hold_flag.sql`：`hold_at_worker_phase boolean DEFAULT false`
- [ ] B1.4 `V{ts}__export_short_links.sql` — **跳过**：P0.1 摸底确认 `ExportApplicationService.toDto` 已经用 TOS 预签名 URL + `download_expires_at` + `revoke` 实现 D5，不需要独立短链表
- [ ] B1.5 `V{ts}__rag_chunks_source_type.sql` — **跳过**：`knowledge_chunks.source_type` 是 `text NOT NULL`，无 CHECK 约束，迁移不需要
- [ ] B1.6 本地 ddl-check（P3 阶段批量验证）

### 3.B adapter（`meeting-api-adapter`）
- [x] B2.1 `MeetingDocumentController`：`POST /api/meetings/{id}/documents` / `DELETE` / `GET`
- [x] B2.2 `MeetingGlossaryController`：`PATCH /api/meetings/{id}/glossary` / `GET`
- [x] B2.3 `ProcessingTaskController`：新增 `POST /:taskId:resume-java-phase`；创建请求 DTO 增 `holdAtWorkerPhase`（可选，默认 false）
- [ ] B2.4 `ExportController`：成功的 `GET /api/meetings/{id}/exports/{jobId}` 响应携带 `downloadUrl`（已存在，待校验）
- [ ] B2.5 `ExportShortLinkController` — **跳过**：见 B1.4
- [ ] B2.6 （D7）`InternalSpeakerReferenceController`（推迟到 P5）

### 3.C app（`meeting-api-app`）
- [x] B3.1 `MeetingDocumentApplicationService.attach/detach/list`：权限校验（user 对 meeting + document 均可访问）、`SECURITY_LEVEL` 取 max、事务内写 outbox `MeetingDocumentAttachedEvent`
- [x] B3.2 `MeetingGlossaryApplicationService.update/read`：事务内更新 `meetings.glossary_terms`，写 outbox `MeetingGlossaryUpdatedEvent`；term 数量上限 ≤200 + 单 term 长度 ≤64
- [x] B3.3 `WorkerPhaseCompletedListener` 增加 hold 分支
- [x] B3.4 `ProcessingTaskResumeApplicationService.resumeJavaPhase(taskId)`：幂等、校验 phase=WORKER_DAG_DONE
- [x] B3.5 `ProcessingTaskApplicationService.create` + `createForCompletedAudioUpload`：构造 task message 时把 `glossaryTerms` + `referenceDocumentIds` 透传进 MQ payload
- [x] B3.6 `MinutesApplicationService.generateForTask`：拼 prompt 时拉 glossary（按 2k char 预算截断，R3）+ 拉 reference document 内容；SECURITY_LEVEL=CONFIDENTIAL/SECRET 由 LlmGateway fail-closed
- [x] B3.7 `MinutesApplicationService.generateForTask` 末尾：事务内写 outbox `MinutesGeneratedEvent` + ApplicationEventPublisher 触发
- [x] B3.8 `MinutesGeneratedRagIndexer`（独立 listener）：消费 `MinutesGeneratedEvent` → 调 `ChunkingApplicationService.rebuildForMeeting`；chunks 落库时 `source_type=MINUTES`
- [ ] B3.9–B3.10 推迟到 P5

### 3.D infrastructure（`meeting-api-infrastructure`）
- [x] B4.1 `JdbcMeetingDocumentRepository` / `JdbcMeetingGlossaryRepository`（D5 已有 `JdbcExportJobRepository`）
- [ ] B4.2 短链 token 生成器 — **跳过**：见 B1.4
- [ ] B4.3 docx 渲染 — **已有**：Apache POI XWPF `DocxExportGateway` (P0.1)
- [ ] B4.4 ArchUnit 白名单更新（自动通过：新包遵循现有 COLA 边界）

### 3.E Java 测试
- [x] B5.1 `MeetingDocumentApplicationServiceTest`（attach/detach/list + 权限拒绝 + 安全级 max + REFERENCE on CONFIDENTIAL fail-closed）
- [x] B5.2 `MeetingGlossaryApplicationServiceTest`（覆盖式更新 + 长度上限 + dedup + outbox 落地）
- [x] B5.3 `ProcessingTaskResumeApplicationServiceTest`（幂等 + 非法 phase 拒绝 + 正常 begin Java phase + task 不存在）
- [x] B5.4 `MinutesGeneratedRagIndexerTest`（消费事件 → 调 rebuildForMeeting → chunks source_type=MINUTES via ChunkingApplicationServiceTest 断言）
- [ ] B5.5 推迟到 P5
- [ ] B5.6 `MeetingFinalizeFlowIT` — 推迟到 R 风险闭环阶段（IT 需 Testcontainers）
- [ ] B5.7 推迟到 P5
- [ ] B5.8 `ExportShortLinkIT` — 跳过：见 B1.4
- [x] B5.9 ArchUnit 测试通过；`./mvnw -DskipITs test` 436 测试全绿

---

## 4. ai-worker 后端 BFF（`apps/ai-worker`）

### 4.A 基础设施
- [ ] C1.1 JWT 校验中间件：拉取 Java JWKS（带缓存 + 轮换）、校验签名 + admin role、失败 401 `UNAUTHENTICATED`
- [ ] C1.2 Java HTTP client 封装：透传用户 JWT + `X-Request-Id` + `X-Trace-Id`，**不持 HMAC**（仅 D7 内部端点用 HMAC client，单独封装）
- [ ] C1.3 进程内会话存储：`uuid → {state, tmp_files, ttl}`，TTL 24h；启动 + 定时清理（cron 5min）
- [ ] C1.4 启动 fail-fast：缺 `JAVA_API_BASE_URL` / `JAVA_JWKS_URL` / `AI_WORKER_INTERNAL_API_HMAC_SECRET`（D7） 时拒启
- [ ] C1.5 `pyright` 通过；新增模块加进 `ai_worker/admin/` 目录树

### 4.B 声纹录入（与单场会议无关）
- [ ] C2.1 `POST /admin/enrollment/sessions` —— 创建会话，返回 sessionId
- [ ] C2.2 `PUT /admin/enrollment/sessions/{id}/audio` —— 接收音频（流式落临时目录）
- [ ] C2.3 `POST /admin/enrollment/sessions/{id}/preview` —— 同步算 embedding + quality_score（不写 Java）
- [ ] C2.4 `POST /admin/enrollment/sessions/{id}/commit` —— 三步编排（Java create profile / audio upload / enrollment）
- [ ] C2.5 `GET /admin/voiceprints?personId=` 透传
- [ ] C2.6 `POST /admin/voiceprints/{enrollmentId}:revoke` 透传

### 4.C 会议工作台
- [ ] C3.1 `GET /admin/persons?q=` 透传 Java 人员搜索
- [ ] C3.2 `POST /admin/meetings` 编排：调 Java 建会议（带 participants）
- [ ] C3.3 `GET /admin/meetings/{id}` 透传：会议 + transcript + speakers + minutes 状态聚合
- [ ] C3.4 `GET /admin/documents?q=` 透传搜索
- [ ] C3.5 `POST /admin/meetings/{id}/documents:attach` 透传
- [ ] C3.6 `PATCH /admin/meetings/{id}/glossary` 透传
- [ ] C3.7 `POST /admin/meetings/{id}:start-processing` 编排：触发 task，**带 `holdAtWorkerPhase=true`**
- [ ] C3.8 `POST /admin/meetings/{id}/speakers/{label}:confirm` 透传
- [ ] C3.9 `POST /admin/meetings/{id}:finalize` 编排：调 Java `resume-java-phase`
- [ ] C3.10 `POST /admin/meetings/{id}/exports` 透传（format=DOCX）
- [ ] C3.11 `GET /admin/meetings/{id}/exports/{jobId}` 透传轮询 + 返回 downloadUrl
- [ ] C3.12 SSE：**前端直连 Java SSE**（不在 worker 维护长连接），worker 仅在文档中说明对接方式

### 4.D D7 真生产实现
- [ ] C4.1 替换 `ReferenceEmbeddingSupplier` 生产实现：调 Java `POST /internal/speakers/reference-embeddings`
- [ ] C4.2 HMAC client 使用 `meeting.ai-worker.hmac-secret`；path 必须为 `/internal/speakers/reference-embeddings` 完整路径（与 signing_string 一致）
- [ ] C4.3 失败回退：5xx → 短重试 3 次指数退避；4xx → 抛 `SpeakerReferenceUnavailable`，由 matching 步骤决定是否降级
- [ ] C4.4 短 TTL 内存缓存（≤60s），key=`(tenantId, sorted(personIds))`；明文向量禁止日志、禁止落盘
- [ ] C4.5 process 退出 / 任务结束时主动 evict 缓存

### 4.E worker 测试
- [ ] C5.1 `tests/admin/test_jwt_middleware.py`：合法/过期/错 audience/缺 role
- [ ] C5.2 `tests/admin/test_enrollment_session.py`：四步链路 + TTL 清理
- [ ] C5.3 `tests/admin/test_meeting_orchestration.py`：hold flag 透传 + finalize 调用 Java resume
- [ ] C5.4 `tests/admin/test_speaker_reference_supplier.py`（respx mock Java）：HMAC 头正确 / 缓存命中 / 401 抛业务异常 / 明文不入日志
- [ ] C5.5 `uv run pyright ai_worker/` 通过
- [ ] C5.6 `uv run pytest tests/ -x -q` 全绿

---

## 5. ai-worker 前端 `apps/ai-worker-web/`

### 5.A 工程骨架
- [ ] D1.1 `apps/ai-worker-web/` 目录初始化：Vite + React 18 + TS strict
- [ ] D1.2 同步 meeting-web 的 lint/test 配置（eslint / prettier / vitest / playwright）
- [ ] D1.3 共享 OpenAPI 类型：`npm run codegen` 生成 `src/shared/api/types.gen.ts`（指向 worker BFF + Java public）
- [ ] D1.4 `npx tsc --noEmit` + `npm test` + `npm run build` 全绿

### 5.B Auth
- [ ] D2.1 未登录 redirect 到 Java `/auth/login?redirect=${worker-admin-url}`
- [ ] D2.2 回跳从 fragment 取 access token 存内存（不 localStorage / sessionStorage）
- [ ] D2.3 refresh 走 Java HttpOnly cookie + `X-CSRF-Token`
- [ ] D2.4 401 拦截器：清内存 token → 跳登录

### 5.C 声纹录入页
- [ ] D3.1 person 选择器（搜索 + 分页）
- [ ] D3.2 录音组件（浏览器 MediaRecorder）+ 上传组件（拖拽 / 选择文件）
- [ ] D3.3 调 worker `/admin/enrollment/sessions/{id}/preview` 拿 quality_score；分数低于阈值警告
- [ ] D3.4 commit 按钮 → 调 worker commit；列表展示已录入 + 撤销按钮

### 5.D 会议工作台（单页向导式）
- [ ] D4.1 **Step 1** 建会议表单：标题、安全级别、language、参会人多选（依赖 D3.1 同款 person 搜索）
- [ ] D4.2 **Step 2** 上传录音：浏览器直传 Java 多分片协议，进度条 + 失败重试 + 断点续传
- [ ] D4.3 **Step 3a** 术语 chip 输入（最多 200，去重，长度上限）+ `PATCH /glossary`
- [ ] D4.3b **Step 3b** 关联文档：搜现有 / 新建（浏览器直传 Java 文档 API → attach），列表展示已关联
- [ ] D4.4 **Step 4** 「开始处理」按钮 → 调 worker `start-processing`（hold=true）；SSE 订阅 worker-DAG 进度（直连 Java SSE）
- [ ] D4.5 **Step 5** 转写预览页：左侧时间线 + 段落列表，每段显示 candidate 候选人，点击触发 `/speakers/{label}:confirm`；候选人需为 D7 真识别结果
- [ ] D4.6 **Step 6a** 「确认 → 生成纪要」按钮 → 调 worker `finalize` → 轮询 / SSE 看 JAVA_LLM_RUNNING 进度
- [ ] D4.6b **Step 6b** 纪要预览渲染（SafeMarkdown，沿用 meeting-web XSS 策略）
- [ ] D4.6c **Step 6c** 「下载 docx」按钮 → 调 worker `exports` → 轮询拿 downloadUrl → 浏览器跳转下载

### 5.E 通用
- [ ] D5.1 统一 error envelope 处理；`error.retryable=true` 时显示重试按钮
- [ ] D5.2 大列表（参会人 / 文档 / 转写段落）虚拟化
- [ ] D5.3 首屏 JS gzip < 200KB（沿用 meeting-web 预算）

### 5.F 测试
- [ ] D6.1 Vitest 覆盖关键 hooks（auth / enrollment / meeting wizard state machine）
- [ ] D6.2 Playwright happy-path E2E：「建会议→上传→术语→开始→认人→出 docx」一条龙
- [ ] D6.3 MSW mock worker BFF；与 Java public API 类型保持同步

---

## 6. infra / 部署 / CI

- [ ] E1.1 `apps/ai-worker/Dockerfile`：multi-stage 加 ai-worker-web 构建产物 `COPY --from=web-build /app/dist /app/admin-ui/`
- [ ] E1.2 ai-worker FastAPI 启动挂静态：`app.mount("/admin", StaticFiles(directory=ADMIN_UI_DIST, html=True))`
- [ ] E1.3 K8s `ai-worker/statefulset.yaml` 环境变量：`JAVA_API_BASE_URL`, `JAVA_JWKS_URL`, `ENROLLMENT_TMP_DIR`, `ADMIN_UI_DIST_PATH`
- [ ] E1.4 K8s secret：`AI_WORKER_INTERNAL_API_HMAC_SECRET`（D7）
- [ ] E1.5 K8s `ai-worker/service.yaml` + Ingress 暴露 `/admin/*`（限内网 / IP whitelist）
- [ ] E1.6 PVC 给 `ENROLLMENT_TMP_DIR`
- [ ] E1.7 `infra/meeting-infra/docker/compose/docker-compose.yml` 补 ai-worker admin UI 端口 + Java JWKS endpoint 配置
- [ ] E1.8 `.github/workflows/ci.yml` 新增 job `ai-worker-web`：`npx tsc --noEmit` + `npm test` + `npm run build`
- [ ] E1.9 CI `contracts` job 覆盖新增的 worker-internal schema（`npm run check` 自动包含）
- [ ] E1.10 CI `ddl-check` 自动覆盖新的 V*.sql

---

## 7. 风险闭环（必须在交付前 ✅）

- [x] R1（docx 渲染）：P0.1 摸底完成 → 现状已确认 = Apache POI XWPF (`DocxExportGateway`)；本期改动 = 不动渲染栈，仅在 `MinutesApplicationService` 注入 glossary + reference document 摘要
- [ ] R2（worker→Java JWT 跨域）：Java ingress CORS 允许 worker 域名，或改后端到后端调用；选定方案 = ____，已在 `meeting-api-start/.../security` 落实
- [x] R3（glossary prompt 长度）：`MinutesApplicationService.buildLlmContext` 注入 glossary + reference 时各预留 1KB（合计 ≤ 2KB），超额截断；`WORKSTATION_CONTEXT_CHAR_BUDGET = 2048`
- [x] R4（参考文档安全级）：`MeetingDocumentApplicationService.attach` 校验 `max(meeting.security_level, document.security_level)`，REFERENCE 角色在 CONFIDENTIAL/SECRET 直接 `SECURITY_LEVEL_BLOCKED`；LlmGateway 二次 fail-closed
- [ ] R5（D7 明文向量通道）：internal-TLS + HMAC + 时间戳 + nonce 全开；response logger redact `values`；日志 sample 抽查 0 明文
- [ ] R6（文档上传断点续传）：复用 Java 现有多分片协议，worker 不参与；前端 D4.2 + D4.3b 已实现并 E2E 验证
- [ ] R7（finalize 后再编辑转写）：仍按现有 STALE 规则（minutes 标 STALE，UI 显式提示用户 regenerate），不做 race 阻塞；测试 `TranscriptEditAfterFinalizeIT`

---

## 8. 阶段化交付与验收

| Phase | 内容 | 完成判据 |
|---|---|---|
| **P1 契约 + Java schema** | §2 全部 + §3.A + §3.B + §3.C 的 B3.1-B3.4, B3.9 + §3.D + §3.E 的 B5.1-B5.3, B5.6 | CI 全绿；契约 PR 合并；DDL 已 ddl-check |
| **P2 Java RAG/glossary 注入 + 纪要入 RAG** | §3.C B3.5-B3.8 + §3.E B5.4, B5.6 扩展 | `MeetingFinalizeFlowIT` 含 minutes 入 RAG 断言 |
| **P3 worker BFF** | §4.A + §4.B + §4.C + §4.E 的 C5.1-C5.3 | `uv run pytest` 全绿；与 Java 联调 happy-path 通 |
| **P4 worker 前端** | §5 全部 | Playwright happy-path 通；同进程挂静态部署可访问 |
| **P5 D7 声纹真生产** | §2.B + §3.B B2.6 + §3.C B3.10 + §3.E B5.5, B5.7 + §4.D + §4.E C5.4 | matching 步骤产出 candidates 来自真识别；明文向量 0 落盘 0 日志 |
| **P6 infra + CI** | §6 全部 | 新 CI job 必跑；docker-compose 一键起 admin UI |

**推荐顺序**：P1 → 并行启动 P2 / P3 / P5 → P4 → P6（实际贯穿）。

---

## 9. Done 的总判据（合并到 master 前都必须 ✅）

- [ ] `cd packages/meeting-contracts && npm run check` ✅
- [ ] `cd packages/meeting-contracts && npm run codegen && git diff --exit-code` ✅
- [ ] `cd apps/meeting-api && ./mvnw verify -q` ✅
- [ ] `cd apps/ai-worker && uv run pyright ai_worker/ && uv run pytest -x -q` ✅
- [ ] `cd apps/meeting-web && npx tsc --noEmit && npm test` ✅
- [ ] `cd apps/ai-worker-web && npx tsc --noEmit && npm test && npm run build` ✅
- [ ] `cd apps/ai-worker-web && npm run e2e`（Playwright happy-path） ✅
- [ ] CI 五个 job + 新 `ai-worker-web` job 全绿
- [ ] `docs/spec.md` / `docs/app-api-contracts.md` 同步更新本期改动
- [ ] `todo.md` 更新进度，本文件 §7 风险闭环全部 ✅
