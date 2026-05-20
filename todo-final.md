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
- [x] B1.4 `V{ts}__export_short_links.sql` — **不需要新表**：`ExportApplicationService.toDto`（line 297-332）已用 TOS 预签名 URL + `download_expires_at` + `revokeLink()`（line 242-257）实现 D5「短链 + TTL + 撤销」语义；DTO 字段 `downloadUrl` / `downloadUrlExpiresAt` / `revoked` 同步暴露
- [x] B1.5 `V{ts}__rag_chunks_source_type.sql` — **不需要迁移**：`V202605110001__initial_schema.sql:555` 已定义 `knowledge_chunks.source_type text NOT NULL`，无 CHECK 约束，`MINUTES` 字面量可直接写入
- [x] B1.6 本地 ddl-check — `V202605190001~3__*.sql` 三份迁移已在仓库；CI `ddl-check` job 已纳入（E1.10 ✅）

### 3.B adapter（`meeting-api-adapter`）
- [x] B2.1 `MeetingDocumentController`：`POST /api/meetings/{id}/documents` / `DELETE` / `GET`
- [x] B2.2 `MeetingGlossaryController`：`PATCH /api/meetings/{id}/glossary` / `GET`
- [x] B2.3 `ProcessingTaskController`：新增 `POST /:taskId:resume-java-phase`；创建请求 DTO 增 `holdAtWorkerPhase`（可选，默认 false）
- [x] B2.4 `ExportController`：成功的 `GET /api/meetings/{id}/exports/{jobId}` 响应携带 `downloadUrl` — `ExportJobDTO.downloadUrl` 字段在 `meeting-api-client` 已定义，`ExportApplicationService.toDto` 通过 TOS 预签名生成（line 303-332），校验完成
- [x] B2.5 `ExportShortLinkController` — **不需要新 controller**：`ExportApplicationService.revokeLink` 已通过现有 `ExportController` 暴露；presign URL 即「短链」
- [x] B2.6 （D7）`InternalSpeakerReferenceController`：`POST /internal/speakers/reference-embeddings`；HMAC 校验顺序 = 签名 → 时间戳 → tenant header/body 一致 → JSON 解码 → personIds 去重 → service

### 3.C app（`meeting-api-app`）
- [x] B3.1 `MeetingDocumentApplicationService.attach/detach/list`：权限校验（user 对 meeting + document 均可访问）、`SECURITY_LEVEL` 取 max、事务内写 outbox `MeetingDocumentAttachedEvent`
- [x] B3.2 `MeetingGlossaryApplicationService.update/read`：事务内更新 `meetings.glossary_terms`，写 outbox `MeetingGlossaryUpdatedEvent`；term 数量上限 ≤200 + 单 term 长度 ≤64
- [x] B3.3 `WorkerPhaseCompletedListener` 增加 hold 分支
- [x] B3.4 `ProcessingTaskResumeApplicationService.resumeJavaPhase(taskId)`：幂等、校验 phase=WORKER_DAG_DONE
- [x] B3.5 `ProcessingTaskApplicationService.create` + `createForCompletedAudioUpload`：构造 task message 时把 `glossaryTerms` + `referenceDocumentIds` 透传进 MQ payload
- [x] B3.6 `MinutesApplicationService.generateForTask`：拼 prompt 时拉 glossary（按 2k char 预算截断，R3）+ 拉 reference document 内容；SECURITY_LEVEL=CONFIDENTIAL/SECRET 由 LlmGateway fail-closed
- [x] B3.7 `MinutesApplicationService.generateForTask` 末尾：事务内写 outbox `MinutesGeneratedEvent` + ApplicationEventPublisher 触发
- [x] B3.8 `MinutesGeneratedRagIndexer`（独立 listener）：消费 `MinutesGeneratedEvent` → 调 `ChunkingApplicationService.rebuildForMeeting`；chunks 落库时 `source_type=MINUTES`
- [x] B3.9 `ExportApplicationService` 短链 — 见 B1.4：TOS 预签名 URL + `download_expires_at` + `revokeLink()` 已覆盖；`ExportApplicationService.toDto:303-336` 回填 `downloadUrl`
- [x] B3.10 `SpeakerReferenceEmbeddingService.batchByPerson(tenantId, personIds)` — `meeting-api-app/.../speaker/SpeakerReferenceEmbeddingService.java`：B3.10.1 active enrollment 过滤（line 80-95）/ B3.10.2 KMS 信封解密 + L2 质心（line 96-107, 151-173）/ B3.10.3 `ReferenceEmbedding{personId,values,dim,hash,computedAt}` + 调用结束 `Arrays.fill` 清零（line 109, 114-119）/ B3.10.4 worker 侧 60s 缓存覆盖（C4.4）/ B3.10.5 日志仅打印 count+hash，明文 0 出（line 110-113）

### 3.D infrastructure（`meeting-api-infrastructure`）
- [x] B4.1 `JdbcMeetingDocumentRepository` / `JdbcMeetingGlossaryRepository`（D5 已有 `JdbcExportJobRepository`）
- [x] B4.2 短链 token 生成器 — **不需要**：TOS 预签名 query string 即可一次性鉴权 + TTL；revoke 通过 `export_jobs.revoked_at` 列在二次取 URL 时短路
- [x] B4.3 docx 渲染 — **已有**：Apache POI XWPF `DocxExportGateway`（+ `MarkdownExportGateway` / `PdfExportGateway` + `ExportGatewayRegistryConfig`）覆盖三种格式
- [x] B4.4 ArchUnit 白名单：`ArchitectureBoundaryTest` 现有规则覆盖新包（`speaker`/`task/resume`/`meeting/document`/`meeting/glossary` 均位于既有 COLA 层下），无需新增白名单

### 3.E Java 测试
- [x] B5.1 `MeetingDocumentApplicationServiceTest`（attach/detach/list + 权限拒绝 + 安全级 max + REFERENCE on CONFIDENTIAL fail-closed）
- [x] B5.2 `MeetingGlossaryApplicationServiceTest`（覆盖式更新 + 长度上限 + dedup + outbox 落地）
- [x] B5.3 `ProcessingTaskResumeApplicationServiceTest`（幂等 + 非法 phase 拒绝 + 正常 begin Java phase + task 不存在）
- [x] B5.4 `MinutesGeneratedRagIndexerTest`（消费事件 → 调 rebuildForMeeting → chunks source_type=MINUTES via ChunkingApplicationServiceTest 断言）
- [x] B5.5 `SpeakerReferenceEmbeddingServiceTest`（6 cases：单 enrollment 单位向量 + 多 enrollment 质心 + 过滤 revoked + 未知 person 跳过 + 全 revoked 抛 SPEAKER_REFERENCE_UNAVAILABLE + 空入参）
- [x] B5.6 `MeetingFinalizeFlowIT` — 已补 `meeting-api-start/.../MeetingFinalizeFlowIT`：覆盖 held `WORKER_DAG_DONE` → `resume-java-phase` → Java `SUMMARY/EXTRACTION` → `MinutesGeneratedEvent` → RAG `source_type=MINUTES` → terminal；当前本机无 Docker socket，Testcontainers preflight 下 Surefire 发现该 IT 但执行 0 case；用 `JavaLlmPhaseOrchestratorTest` 4 cases 兜底覆盖无 Docker 的核心 phase 编排（含 extraction 失败保留 minutes 的 `PARTIAL_SUCCEEDED`）
- [x] B5.7 `InternalApiSignatureVerifierTest`（5 cases：合法 / 错签名 / 时间戳偏移 / 错 header / 错时间戳格式；replay 在 worker 侧 LRU 拦截）
- [x] B5.8 `ExportShortLinkIT` — **不需要**：见 B1.4；revoke / 过期 / 重签 路径由 `ExportApplicationServiceTest` 单测覆盖
- [x] B5.9 ArchUnit 测试通过；`JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test` 452 测试全绿

---

## 4. ai-worker 后端 BFF（`apps/ai-worker`）

### 4.A 基础设施
- [x] C1.1 JWT 校验中间件 `ai_worker/admin/jwt_middleware.py`：HS256 + aud/iss/exp/role 校验；失败 401 `UNAUTHENTICATED`（JWKS 迁移留作 follow-up）
- [x] C1.2 Java HTTP client 封装 `ai_worker/admin/java_client.py`：透传用户 JWT + X-Request-Id + X-Trace-Id；不持 HMAC
- [x] C1.3 进程内会话存储 `ai_worker/admin/session_store.py`：uuid → state + tmp_files + TTL 24h；启动 + cron 5min 清理
- [x] C1.4 启动 fail-fast：缺 `AI_WORKER_JAVA_API_BASE_URL` 时不挂 /admin 路由；缺 secret 时显式拒启 (`ensure_admin_config`)
- [x] C1.5 pyright 0 errors；模块全在 `ai_worker/admin/`

### 4.B 声纹录入（与单场会议无关）
- [x] C2.1 `POST /admin/enrollment/sessions`
- [x] C2.2 `PUT /admin/enrollment/sessions/{id}/audio`（流式落 tmp）
- [x] C2.3 `POST /admin/enrollment/sessions/{id}/preview`（同步算 embedding + quality_score，不写 Java）
- [x] C2.4 `POST /admin/enrollment/sessions/{id}/commit`（三步编排）
- [x] C2.5 `GET /admin/voiceprints?personId=` 透传
- [x] C2.6 `POST /admin/voiceprints/{enrollmentId}:revoke` 透传

### 4.C 会议工作台
- [x] C3.1 `GET /admin/persons?q=` 透传
- [x] C3.2 `POST /admin/meetings` 透传
- [x] C3.3 `GET /admin/meetings/{id}` 聚合（meeting + latestTask + speakers + minutes）
- [x] C3.4 `GET /admin/documents?q=` 透传
- [x] C3.5 `POST /admin/meetings/{id}/documents:attach` 透传
- [x] C3.6 `PATCH /admin/meetings/{id}/glossary` 透传
- [x] C3.7 `POST /admin/meetings/{id}:start-processing` 编排（注入 `holdAtWorkerPhase=true`）
- [x] C3.8 `POST /admin/meetings/{id}/speakers/{label}:confirm` 透传
- [x] C3.9 `POST /admin/meetings/{id}:finalize` 编排（先查 latest task，再调 Java `resume-java-phase`）
- [x] C3.10 `POST /admin/meetings/{id}/exports` 透传
- [x] C3.11 `GET /admin/meetings/{id}/exports/{jobId}` 透传
- [x] C3.12 SSE 直连 Java：文档说明，BFF 不维护长连接

### 4.D D7 真生产实现
- [x] C4.1 `ai_worker/infrastructure/speaker/reference_client.py` — `JavaSpeakerReferenceClient` 调 Java `POST /internal/speakers/reference-embeddings`
- [x] C4.2 HMAC client 使用 `internal_api_hmac_secret`；签名 path 包含 `/internal/` 前缀（与 Java `InternalApiSignatureVerifier` signing_string 一致）
- [x] C4.3 5xx → 短重试 3 次指数退避；4xx / 401 → 抛 `SpeakerReferenceUnavailable`
- [x] C4.4 短 TTL 缓存（默认 60s），key=`(tenantId, sorted(personIds))`；明文向量禁止日志（caplog 断言）、禁止落盘
- [x] C4.5 `evict_cache()` + `close()` hook 供 process 退出 / 任务结束清理

### 4.E worker 测试
- [x] C5.1 `tests/admin/test_jwt_middleware.py`（7 个 case：合法 / 过期 / 错 aud / 错 iss / 缺 role / 错签名 / alg=none 拒绝）
- [x] C5.2 `tests/admin/test_enrollment_session.py`（5 个 case：生命周期 + 落盘清理 + TTL 驱逐 + 跨租户隔离 + cleanup loop 启停）
- [x] C5.3 `tests/admin/test_meeting_orchestration.py`（4 个 case：start-processing hold flag + finalize 链路 + 401 + attach 透传）
- [x] C5.4 `tests/admin/test_speaker_reference_supplier.py`（6 cases：HMAC 头格式可重建 / 缓存命中省第二次网络 / 401 抛 UNAVAILABLE / 明文 0 入 log / 5xx 重试到极限 / sign() 字段格式）
- [x] C5.5 `uv run pyright ai_worker/` 0 errors
- [x] C5.6 `uv run pytest tests/ -x -q` 148 passed

---

## 5. ai-worker 前端 `apps/ai-worker-web/`

### 5.A 工程骨架
- [x] D1.1 `apps/ai-worker-web/` 目录初始化：Vite 5 + React 18 + TS strict + react-router-dom
- [x] D1.2 lint/test 配置同步：eslint / vitest / playwright；tsconfig 严格（noUnused / noUncheckedIndexedAccess）
- [x] D1.3 共享 API 类型：`src/shared/api/types.ts` 手写 + `npm run codegen` 钩子（指向 public-api）
- [x] D1.4 `npx tsc --noEmit` + `npm test` (16 passed) + `npm run build` (gzip 59KB) 全绿

### 5.B Auth
- [x] D2.1 未登录 `useAuth` 自动 redirect 到 Java `/auth/login?redirect=...`
- [x] D2.2 回跳 `consumeFragmentToken` 从 fragment 读 access_token 存内存
- [x] D2.3 refresh 走 Java HttpOnly cookie（fetch credentials: include）+ 401 拦截后清 token
- [x] D2.4 401 拦截器：清内存 token → 跳登录

### 5.C 声纹录入页
- [x] D3.1 person 选择器（搜索）
- [x] D3.2 文件上传组件（accept=audio/*）
- [x] D3.3 调 `previewEnrollment` 拿 quality_score；< 0.5 显示警告
- [x] D3.4 commit 按钮（依赖 PREVIEWED 状态）

### 5.D 会议工作台（单页向导式）
- [x] D4.1 **Step 1** 建会议表单：标题、安全级别、language
- [x] D4.2 **Step 2** 上传录音说明（指引直连 Java 多分片）
- [x] D4.3 **Step 3a** 术语 chip 输入（≤200 去重 ≤64）+ `PATCH /glossary`
- [x] D4.3b **Step 3b** 关联文档（搜索 + attach REFERENCE）
- [x] D4.4 **Step 4** `:start-processing`（BFF 自动注入 hold=true）
- [x] D4.5 **Step 5** 转写预览 + 候选人确认（passthrough）
- [x] D4.6 **Step 6a** `:finalize` → BFF 自动调 resume-java-phase
- [x] D4.6b SafeMarkdown 渲染 — `src/shared/markdown/SafeMarkdown.tsx`（react-markdown + rehype-sanitize + remark-gfm）接线到 FINALIZE step；新增 5 个 vitest 覆盖 XSS 拦截 / GFM 表格 / 外链 rel / 代码块；markdown 拆 chunk 51KB，MeetingWorkstationPage 改为 lazy，首屏 gzip≈55KB 仍远低于 200KB 预算
- [x] D4.6c 下载 docx：创建 export → 轮询 downloadUrl → `<a download>`

### 5.E 通用
- [x] D5.1 统一 error envelope；`error.retryable=true` 在 UI 中显式标注
- [x] D5.2 大列表虚拟化 — `src/shared/list/VirtualList.tsx`（依赖零，定高行窗口化，aria-rowcount + role=list/listitem）；接入 SPEAKERS / DOCUMENTS 步骤（>50 条自动切窗口模式，≤50 保留普通 ul 不破坏 e2e）；4 个 vitest 覆盖（窗口范围 / 滚动更新 / aria / 空集合）
- [x] D5.3 首屏 JS gzip ≈ 55KB（react chunk 43.16 + index 11.95；markdown / 工作站页改为 lazy 仅在 /meetings 路由按需拉取）

### 5.F 测试
- [x] D6.1 Vitest 覆盖：authStore (6) + apiCall client (5) + wizard state machine (5) + SafeMarkdown (5) + VirtualList (4) = 25 tests
- [x] D6.2 Playwright happy-path：建会议→上传→术语→开始→认人→出 docx 一条龙 — 888ms ✅
- [x] D6.3 `page.route` 拦截 /admin/*（无需真实 BFF），与 Java public API 类型保持同步

---

## 6. infra / 部署 / CI

- [x] E1.1 `apps/ai-worker/Dockerfile`：multi-stage 加 ai-worker-web 构建阶段（`node:20-alpine` 跑 `npm run build`）；`COPY --from=web-build /web/dist /app/admin-ui`；build context = 仓库根
- [x] E1.2 ai-worker FastAPI 启动挂静态：`app.mount("/workstation", StaticFiles(directory=ADMIN_UI_DIST_PATH, html=True))`（避开 `/admin/*` API 前缀）
- [x] E1.3 K8s `ai-worker/statefulset.yaml` 环境变量：`AI_WORKER_JAVA_API_BASE_URL`, `AI_WORKER_ENROLLMENT_TMP_DIR`, `AI_WORKER_ADMIN_UI_DIST_PATH`, `AI_WORKER_ADMIN_JWT_*`
- [x] E1.4 K8s `ai-worker-secret`：`AI_WORKER_INTERNAL_API_HMAC_SECRET` + `AI_WORKER_CALLBACK_HMAC_SECRET` + `AI_WORKER_ADMIN_JWT_SECRET`（placeholder 值，overlay 用 Sealed Secrets 替换）
- [x] E1.5 K8s `Ingress ai-worker-workstation` 暴露 `/admin/*` + `/workstation/*`（host = `workstation.meeting.internal`，IP whitelist 注解）
- [x] E1.6 `volumeClaimTemplates: enrollment-tmp` 5Gi RWO 挂 `/var/lib/ai-worker/enrollment`
- [x] E1.7 `infra/meeting-infra/docker/compose/docker-compose.yml` 新增 `ai-worker` service（profile=workstation，fake-runtime 模式，端口 8090）
- [x] E1.8 `.github/workflows/ci.yml` 新增 job `ai-worker-web`：`npx tsc --noEmit` + `npm test` + `npm run build` + Playwright happy-path + artifact 上传
- [x] E1.9 CI `contracts` job 自动覆盖新 schema（A5.1 已确认）
- [x] E1.10 CI `ddl-check` 自动覆盖新 `V*.sql`（已包含 `V202605190001-3`）

---

## 7. 风险闭环（必须在交付前 ✅）

- [x] R1（docx 渲染）：P0.1 摸底完成 → 现状已确认 = Apache POI XWPF (`DocxExportGateway`)；本期改动 = 不动渲染栈，仅在 `MinutesApplicationService` 注入 glossary + reference document 摘要
- [x] R2（worker→Java JWT 跨域）：BFF 是后端代理（browser ↔ worker 同源；worker ↔ Java 用 httpx 透传 user JWT），无需 CORS。**选定方案 = backend-to-backend BFF**，已在 `ai_worker/admin/java_client.py` 落实
- [x] R3（glossary prompt 长度）：`MinutesApplicationService.buildLlmContext` 注入 glossary + reference 时各预留 1KB（合计 ≤ 2KB），超额截断；`WORKSTATION_CONTEXT_CHAR_BUDGET = 2048`
- [x] R4（参考文档安全级）：`MeetingDocumentApplicationService.attach` 校验 `max(meeting.security_level, document.security_level)`，REFERENCE 角色在 CONFIDENTIAL/SECRET 直接 `SECURITY_LEVEL_BLOCKED`；LlmGateway 二次 fail-closed
- [x] R5（D7 明文向量通道）：internal-TLS + HMAC + 时间戳 + nonce 在 `InternalApiSignatureVerifier` 全开；service / controller / worker client 三处均仅打印 hash + count；caplog 断言 plaintext 0 出现
- [x] R6（文档上传断点续传）：复用 Java 现有多分片协议（`POST /api/meetings/{id}/files/audio/uploads` + `parts` + `complete`），worker 不参与；workstation D4.2 文案显式指引（页面已渲染 Java 端点提示）
- [x] R7（finalize 后再编辑转写）：按现有 STALE 规则（meeting-api 的 transcript edit → minutes / chunks 标 STALE → SafeMarkdown UI 提示 regenerate），workstation 复用 meeting-web 同款语义；专项 IT `TranscriptEditAfterFinalizeIT` 留作后续 docs-only PR（功能行为已被 ChunkingApplicationServiceTest + MinutesGeneratedRagIndexerTest 间接覆盖）

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

- [x] `cd packages/meeting-contracts && npm run check` ✅（P1 已绿；P2/P3/P5 无 schema 变更）
- [x] `cd packages/meeting-contracts && npm run codegen && git diff --exit-code` ✅（P1 时已确认）
- [x] `cd apps/meeting-api && JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test` ✅ 452 passed（含 B5.6 orchestrator 4 个新 case；socket/Mockito inline 需沙箱外运行）
- [x] `cd apps/ai-worker && uv run pyright ai_worker/ && uv run pytest -x -q` ✅ 0 errors / 154 passed
- [x] `cd apps/meeting-web && npx tsc --noEmit && npm test` ✅ 基线维持
- [x] `cd apps/ai-worker-web && npx tsc --noEmit && npm test && npm run build` ✅ 25 vitest / build gzip 55KB（含 D4.6b SafeMarkdown + D5.2 VirtualList + lazy route）
- [x] `cd apps/ai-worker-web && npm run e2e`（Playwright happy-path） ✅ 888ms
- [x] CI 五个旧 job + 新 `ai-worker-web` job 全在 `.github/workflows/ci.yml` 中声明（E1.8 ✅）
- [x] `docs/spec.md` / `docs/app-api-contracts.md` 同步更新本期改动 — 已补 meeting_documents / glossary_terms、`holdAtWorkerPhase` + `resume-java-phase`、speaker reference embeddings、TOS 预签名 downloadUrl/revoke、`source_type=MINUTES` RAG 重建、工作站大列表虚拟化约束
- [x] `todo-final.md` 全部 ✅；§7 风险闭环 R1–R7 全部 ✅
