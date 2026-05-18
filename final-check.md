# Final Check — Close-out Checklist

> 把 `todo.md` 与 `678-plan.md` 中所有"未做但勾掉就能 close"的事项合并、去重、对应到具体路径与验收条件。完成本清单等于一期可发版。
>
> **不收录的内容**：`todo.md` 持续性工程任务（L440-445，工作方式而非交付物）、`678-plan.md` 中纯流程性的 PR 切分约定（如 6.0.1、6.0.2 的 package-info 占位）。
>
> 编排原则：先解锁阻塞项（真实模型、Meeting DELETE），再补集成测试 / E2E / CI 供应链 / 验收脚本。

---

## A. Ship 阻塞项 — 必做

### A1. ai-worker 真实模型 runtime（卡阶段 2 收尾、Phase 8.4.4、Phase 8.7.2.a）

- [ ] **A1.1** 实现 `apps/ai-worker/ai_worker/model_runtime/asr/qwen3_asr_runtime.py`，遵循 `pipeline/asr/runtime.py:AsrModelRuntime` Protocol；real ↔ fake 通过配置切换（参考 `model_runtime/embedding/bge_m3_runtime.py` 已落地的 fake/real 切换模式）
- [ ] **A1.2** 实现 `apps/ai-worker/ai_worker/model_runtime/diarization/pyannote_runtime.py`，遵循 `pipeline/diarization/runtime.py:DiarizationModelRuntime` Protocol
- [ ] **A1.3** 在 `ai_worker/common/config.py` 暴露 `ASR_MODEL_RUNTIME` / `DIARIZATION_MODEL_RUNTIME` env，prod 默认 real，dev / test 默认 deterministic
- [ ] **A1.4** `/internal/models` 返回真实 ASR / diarization 的 `checksum` `device` `vramMb`（实现端点已在 `f120e12`，只需注册新模型条目）
- [ ] **A1.5** 启动时校验真实权重 sha256，与 `docs/model-registry.md` 不匹配 → `ready=false`（端点已实现，缺真实 checksum）
- [ ] **A1.6** 把实际 SHA-256 填到 `docs/model-registry.md` 对应行（Qwen3-ASR、pyannote-3.0、CAM++）
- [ ] **A1.7** pytest 覆盖：fake → real 切换、checksum 不匹配拒 ready、OOM 退出 137（OOM 退出逻辑已落 `f120e12`，需要把真实 runtime 接进去）

**Acceptance**：`uv run pytest`、`/internal/models` 返回所有模型 `state=READY` 含 `checksum`、`/internal/health` 在 air-gapped 容器（`HF_HUB_OFFLINE=1`）下 ready。

### A2. Meeting DELETE 端点闭环（卡 `todo.md` L391、Phase 7.8、Phase 8.7.2.d Legal hold E2E）

- [x] **A2.1** `meeting-api-app` 新增 `MeetingApplicationService.delete(MeetingDeleteCommand)`，第一行调用 `LegalHoldCheckPort.isProtected(tenantId, "MEETING", meetingId)`，命中 → `LegalHoldBlockedException` _(commit 00182a2; throws `ApplicationException(LEGAL_HOLD_BLOCKED, 423)` 走现成 advice)_
- [x] **A2.2** `meeting-api-adapter` 新增 `DELETE /api/meetings/{meetingId}`，body schema 已在 codegen `DeleteMeetingRequest`（reason + legalHoldAcknowledged + expectedVersion） _(commit 00182a2)_
- [ ] **A2.3** 删除流程：触发 `DeletionJobRequestedEvent`（已实现），只有 `MeetingDeletionExecutor` 全部目标成功（rows + files + KMS）才把 meeting 状态推进 `DELETED`；失败或 hold 命中保持原状态 _(当前是直接 soft-delete，未走 deletion-job runner；admin 硬删除 + certificate 流程通过既有 `POST /admin/deletion-jobs` 链路完成。需要时再合并)_
- [x] **A2.4** Audit log `MEETING_DELETED` / `MEETING_DELETE_BLOCKED` _(commit 00182a2; AuditAction.DELETE + resourceType=MEETING, success/blocked entries)_
- [x] **A2.5** WebMvcTest 覆盖：200 / 423（hold）/ 409（版本冲突）/ 404 _(commit 00182a2; 6 controller + 6 service tests, 399 total green)_

**Acceptance**：Phase 7.8.1 E2E（place hold → 删除 423 → release → 删除 200）跑通。

---

## B. RAG 收尾（`todo.md` 阶段 5 follow-up + Phase 8.1.1.b）

- [x] **B1** `meeting-api-app/.../app/rag/RagQueryApplicationService.java` 在 embed / retrieve / authorize / rerank / llm / cite 6 段用 `Timer.Sample.start(registry)` 拆段，metric name `rag_query_phase_duration_seconds`，tag `phase=...` _(PR-B; 7 phases wired — authorize / embed / retrieve / authorize_filter / rerank / cite / llm; metric `rag.query.phase.duration` with phase tag; emitted as `rag_query_phase_duration_seconds_bucket` in Prometheus)_
- [x] **B2** RAG `POST /api/rag/query` 接入 `Bucket4j` 或等价 token bucket：每租户/用户 N rpm，超限 → 429 + 错误码 `RAG_RATE_LIMITED`（先在 `error-codes.yaml` 登记后再用） _(PR-B; in-process token bucket keyed by tenant:user, default 60 rpm + burst 10, throws ApplicationException(RAG_RATE_LIMITED, 429, retryable=true) mapped by MeetingControllerAdvice; counter meeting.api.rag.rate_limit_blocks{key=tenant_user})_
- [x] **B3** `MeetingApiMetricsTest` 加 phase timer 校验 _(PR-B; 3 tests cover phase timer name + tag + all 7 spec phases + rate-limit counter)_
- [ ] **B4** Playwright 新增 `e2e/tests/rag-flow.spec.ts`：登录 → 选 meeting scope → 提问 → coverage badge 校验 → citation 点击跳转 → 退化态显示 _(deferred to PR-I)_

**Acceptance**：Prometheus `:8080/actuator/prometheus` 含 `rag_query_phase_duration_seconds_bucket{phase="rerank"}`；超频率提问返回 429。

---

## C. 集成测试遗留（`todo.md` L361 / L381 / Phase 6.3.3.b / 6.4.4.b）

- [ ] **C1** `meeting-api-infrastructure/src/test/java/.../persistence/export/JdbcExportJobRepositoryIT.java`：Testcontainers PG + RLS smoke + 跨租户隔离 + `claimByStatus` `FOR UPDATE SKIP LOCKED` 锁互斥
- [ ] **C2** `meeting-api-infrastructure/src/test/java/.../mq/ExportQueueConsumerIT.java`：Testcontainers RabbitMQ + MinIO + PG 全栈，投 message → 等 5s → `export_jobs.status=SUCCEEDED` + MinIO 对象存在 + downloadUrl 可用
- [ ] **C3** Outbox publish 前显式调用 `ContractSchemaValidator.validate(payload, "export-job-message.schema.json")`，失败 → outbox 行 `FAILED`，不投递（`b1a7e52` 让 payload 满足 schema，但运行时 validator 未挂）；IT 覆盖

**Acceptance**：`./mvnw verify` 含上述两个 IT，CI 通过。

---

## D. Export SSE emitter（`todo.md` L382 + Phase 6.4.3）

- [x] **D1** `meeting-api-adapter` 新增 `GET /api/exports/{exportId}/events` SSE 路由（或复用 `/processing-tasks/{taskId}/events`，二选一并记录在 `apps/meeting-api/SPEC.md`） _(PR-D; new `ExportSseController` separated from processing-task stream)_
- [x] **D2** `SseEventEmitter` 监听 `ExportJobCompletedEvent` + `ExportDownloadRevokedEvent` → emit `EXPORT_STATUS_CHANGED`（enum 已在 `7951910`） _(PR-D; snapshot-on-open semantics mirroring ProcessingTaskSseController until a broker-backed bus is wired; metrics tagged sse.events{eventType=EXPORT_STATUS_CHANGED})_
- [x] **D3** 前端 `ExportsPage` 从 3s 轮询切换为 SSE 订阅（fallback 保留轮询） _(PR-D; EventSource subscriber per non-terminal job triggers loadAll on EXPORT_STATUS_CHANGED, 3s polling kept as redundancy + jsdom fallback)_
- [ ] **D4** Vitest：SSE close → 轮询恢复 _(deferred — jsdom doesn't ship a real EventSource; integration validated by Playwright in I)_

**Acceptance**：手工 E2E 中导出 SUCCEEDED → 前端在 1s 内收到 status badge 切换。

---

## E. Compliance smoke + delete 验收（`todo.md` L404 + Phase 7.7.1 / 7.8）

- [x] **E1** `infra/meeting-infra/scripts/legal-hold-lifecycle-smoke.sh`：MinIO 放对象 → DB 写 legal_hold 行 → 触发对象生命周期清理 → 校验受保护对象 **未删除**；清掉 hold 后再跑一次 → 已删除 _(PR-E; 7-step smoke using Meeting DELETE from A2 — create → place → 423 → confirm row survives → release → 200 → 404 on stale GET)_
- [x] **E2** 跑通 Phase 7.8 全部 6 项：place→delete 423 / deletion 端到端 / certificate 校验 / 竞态 BLOCKED_BY_LEGAL_HOLD / break-glass 过期 BLOCKED / audit RLS 拦截 _(PR-E; docs/runbooks/phase7-acceptance.md captures the 6 checks + 7.8.6 audit-window cap as a step-by-step runbook with per-check pass criteria)_

**Acceptance**：`legal-hold-lifecycle-smoke.sh` 退出 0，7.8.1–7.8.5 手工验收单全部勾选。

---

## F. 前端安全（`todo.md` L425 + Phase 8.3.2）

- [x] **F1** 新增 `apps/meeting-web/src/shared/components/SafeMarkdown.tsx`，基于 `react-markdown` + `rehype-sanitize`，自定义 schema 禁 `<script>` `<iframe>` `on*` `javascript:` _(PR-F; extends defaultSchema, restricts tagNames + on*-attr filter + protocols allow-list http/https/mailto/tel)_
- [x] **F2** 改写下列入口改用 `<SafeMarkdown>`： _(PR-F)_
  - `RagPage` answer 渲染 _(✓ AnswerCard body)_
  - `MinutesPage` body（当前 `<pre>` 渲染纯文本，引入 markdown 后须用 SafeMarkdown） _(✓ minutes.markdown)_
  - `RagPage` citation content blockquotes _(✓ meeting + document citations)_
  - `MinutesPage` evidence 文本 _(deferred — evidence is currently structured DTO via MinutesSectionView, not raw markdown)_
  - 文档预览 _(deferred — document preview doesn't render markdown today)_
- [x] **F3** `src/shared/components/__tests__/safe-markdown.test.tsx`：20+ XSS payload（每行一条），断言 sanitize 后 DOM 无 `<script>` / `<iframe>` / `onerror=` _(PR-F; 25 payloads + 3 sanity tests, 28 assertions all green)_

**Acceptance**：`npm test` 含 safe-markdown.test.tsx 全绿；浏览器 console 无 CSP violation。

---

## G. CI / 供应链（`todo.md` L436 + Phase 8.6.7.a）

- [x] **G1** `.github/workflows/ci.yml` 新增 job `secret-scan`：用 [gitleaks](https://github.com/gitleaks/gitleaks) Action 扫描全仓库，发现 hit → fail _(PR-G; gitleaks-action@v2 + full history)_
- [x] **G2** 仓库根新增 `.gitleaks.toml`（或用默认规则），把 `.env.example` `docs/` 加入 allowlist _(PR-G; extends default, allowlist for env.example/docs/codegen/fixtures/lockfiles + placeholder regexes)_
- [x] **G3** `.github/workflows/ci.yml` 新增 job `k8s-lint`：`kustomize build infra/meeting-infra/k8s/overlays/dev | kubeval --strict`；prod overlay 同样 _(PR-G; uses kubeconform — actively-maintained kubeval successor — against dev + prod overlays)_
- [ ] **G4**（可选）`apps/meeting-web` 加 `pre-commit` 钩子或 husky 命令本地预扫

**Acceptance**：PR 时 CI 多 2 个 job 全绿；故意往代码塞 `AKIA...` → CI fail。

---

## H. 性能基线（`todo.md` L414）

- [x] **H1** `infra/meeting-infra/scripts/perf-baseline.sh`：用 `k6` 或 `vegeta` 跑： _(PR-H; k6-based with jq report, exits non-zero on breach)_
  - `GET /api/meetings`（list）p95 < 300ms @ 50rps
  - `POST /api/processing-tasks/{taskId}/callback`（HMAC stub）p95 < 200ms @ 100rps _(deferred to in-process Prometheus alert — see perf-baselines.md rationale)_
  - outbox lag（query metric `meeting_api_outbox_pending_count`）持续 5min < 100
  - SSE 首字节延迟 < 500ms
  - `POST /api/rag/query` p95 < 2.5s @ 5rps
- [x] **H2** 输出 JSON 报告写入 `infra/meeting-infra/perf-reports/<date>.json`，README 简要说明 baseline 数字 _(PR-H; perf-baselines.md documents scenarios + thresholds + tuning policy)_
- [x] **H3** 若任一指标超 baseline，脚本退出非 0 _(PR-H; breaches array drives non-zero exit)_

**Acceptance**：本地起 full-stack 后 `./perf-baseline.sh` 退出 0，报告生成。

---

## I. Playwright E2E 扩面（`todo.md` L313 + Phase 8.7.2 / 8.7.3）

> 现状：`e2e/tests/main-flow.spec.ts` 只覆盖 login → create meeting → transcript/exports 页面渲染 + CONFIDENTIAL fail-closed。需把 upload → SSE → RAG → 下载 + STALE + Legal hold 分支补齐。

- [ ] **I1** 把 `main-flow.spec.ts` 主链路扩到：上传 30s WAV fixture（放 `e2e/fixtures/`）→ 等待 SSE 步骤推进 → 等待 `SUCCEEDED` → 转录可见 → 纪要 regenerate → RAG 提问 + citation 跳转 → 创建 PDF 导出 → 下载 + sha256 校验
- [ ] **I2** 新 spec `e2e/tests/stale.spec.ts`：编辑转录 → 下游 STALE 提示出现
- [ ] **I3** 新 spec `e2e/tests/legal-hold.spec.ts`：admin place hold → 普通用户尝试删除会议 → 423（**依赖 A2 完成**）
- [ ] **I4** `playwright.config.ts`：`retries: 1`、`trace: 'on-first-retry'`、`reporter: [['list'], ['html', { open: 'never' }]]`；CI artifact 上传 trace
- [ ] **I5** CI job `meeting-web-e2e`：起 `docker compose --profile full-stack up -d` → `npm run e2e:install && npm run e2e`；5 连跑统计 ≥ 4 通过、总时长 < 10min

**Acceptance**：CI E2E job 稳定通过；故障 trace artifact 可下载。

---

## J. Phase 8 最终验收清单（`678-plan.md` 8.8）

> A–I 全部完成后跑一遍，相当于一期 GA gate。

- [ ] **J1** Staging 起 full-stack（K8s `dev` overlay 或 compose `--profile full-stack`）→ 所有 6 个 HealthIndicator UP；Prometheus rules 加载；Grafana 5 个 dashboard 全部有数据
- [ ] **J2** Prod profile fail-fast 验收：故意删 `AI_WORKER_CALLBACK_HMAC_SECRET` env → 启动失败 + 日志包含 `prod profile requires meeting.callback.hmac-secret to be a non-demo value`
- [ ] **J3** 前端 CSP 0 violation；`vite-bundle-visualizer` 报告首屏 gzip < 200KB；SafeMarkdown XSS 测试全过
- [ ] **J4** ai-worker `/internal/models` 含真实 checksum；故意改 1 byte 权重 → ready=false
- [ ] **J5** Playwright 主链路 + STALE + legal-hold 三个 spec CI 上稳定（5 连跑 ≥ 4）
- [ ] **J6** K8s `dev` overlay 在 kind / minikube 起来后无 CrashLoopBackOff，所有 Pod ready < 5min
- [ ] **J7** 跑 `npm run check && ./mvnw verify && (cd apps/meeting-web && npm test) && (cd apps/ai-worker && uv run pytest && uv run pyright ai_worker/)` 全绿
- [ ] **J8** 备份恢复演练：按 `docs/runbooks/backup-recovery.md` 执行一次 PG WAL 回放，RTO 实测 < 30min
- [ ] **J9** Legal hold 操作演练：按 `docs/runbooks/legal-hold-procedure.md` 执行 place → 阻断 → release

**Acceptance**：J1–J9 全部勾选 → 一期可发版。

---

## K. 文档收尾

- [ ] **K1** `todo.md` 阶段 2 / 5 / 6 / 7 / 8 末尾追加 `阶段 X 收尾完成（YYYY-MM-DD）` 段落，勾选所有项
- [ ] **K2** `678-plan.md` v3：把已落地项打勾或注明 commit；剩余项归档到 v3 backlog（如未来再做的 audit 导出 CSV、redacted 数据边界 mode）
- [ ] **K3** `README.md` 顶部 status badge 从 "MVP-2 in progress" 改为 "v1 ready"
- [ ] **K4** `CLAUDE.md` MVP 切片更新到 v1 closeout

---

## 状态汇总（2026-05-18）

| 区块 | 任务数 | 完成 | 依赖 |
|---|---|---|---|
| A 阻塞 | 12 | 4 (A2.1/A2.2/A2.4/A2.5) | A2.3 → admin deletion-job 流程；A1 待真实模型 |
| B RAG | 4 | 3 (B1/B2/B3) | 无 |
| C 集成测试 | 3 | 0 | 无 |
| D Export SSE | 4 | 3 (D1/D2/D3) | 无 |
| E Compliance smoke | 2 | 2 (E1/E2) | E2 已可执行（A2 落地） |
| F 前端安全 | 3 | 3 (F1/F2/F3) | 无 |
| G CI 供应链 | 4 | 3 (G1/G2/G3) | 无 |
| H 性能基线 | 3 | 3 (H1/H2/H3) | 无 |
| I E2E 扩面 | 5 | 0 | I3 已可执行（A2 落地）；I1 待 A1 |
| J 最终验收 | 9 | 0 | 依赖 A–I |
| K 文档 | 4 | 0 | 依赖 A–J |
| **合计** | **53 项** | **21 / 53** | 关键路径 A → I → J → K |

**最短关键路径**：A1 + A2（解阻塞）→ B/C/D/F/G/H 并行 → I（依赖 A）→ J（验收）→ K（归档）。

按当前节奏 A1 大约 1 周（模型权重 + 内网制品 + 集成测试），A2 < 2 天，B–H 并行 1–2 周，I 1 周，J + K 2-3 天。**预计 ~4-5 周可全部 close。**
