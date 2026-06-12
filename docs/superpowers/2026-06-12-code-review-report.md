# Code Review Report — Four-Workspace Sweep (2026-06-12)

> Full-codebase review of business logic and UI click/interaction logic, performed by four parallel reviewer agents (one per workspace) on commit `9143010`. This report is the source document for the remediation plan volumes under `plans/2026-06-12-review-remediation-*.md`.

**Verdict: no workspace is production-ready.** The architectural skeleton is faithful to the spec — HMAC dual-secret separation, callback idempotency, KMS envelope encryption, outbox pattern, SafeMarkdown sanitization, memory-only tokens all check out. The defects concentrate in (a) the orchestration glue (post-callback LLM phase, lease lifecycle, the never-exercised production consume path) and (b) interaction details (idempotency keys, cache invalidation, SSE reconnect).

Totals: **14 Critical, 42 Important**, plus minors.

---

## apps/meeting-api (Java) — 5 Critical, 10 Important

### Critical

| # | Finding | Where |
|---|---|---|
| C1 | Worker `/complete` callback synchronously runs the entire Java LLM phase (2× DashScope + RAG re-chunk) on the callback request thread — `@TransactionalEventListener(AFTER_COMMIT)` with no `@Async`, no `@EnableAsync` anywhere | `WorkerPhaseCompletedListener.java:70-89` |
| C2 | LLM / embed / rerank HTTP calls wrapped inside DB transactions (SPEC §7 rule 4 violation) — pool exhaustion under modest RAG concurrency. `ExportRenderService` is the correct three-phase template | `RagQueryApplicationService:180-192`, `MinutesApplicationService:144-165`, `ExtractionApplicationService:97-112` |
| C3 | Post-commit orchestration reads run without tenant context — `app.tenant_id` is transaction-local, so under real RLS the LLM phase silently never runs. Masked today because Testcontainers/compose DB users are **superusers** (bypass RLS) | `WorkerPhaseCompletedListener:74`, `JavaLlmPhaseOrchestrator:42-100`, `TenantSessionContext.java:31-34` |
| C4 | Lease/state-machine holes strand tasks permanently: lease never cleared at `completeWorkerPhase` (every `holdAtWorkerPhase` task waiting >5 min is ORPHANED); `requeueOrphaned` / `confirmCancelled` have zero callers; `retry()` republishes no MQ message | `ProcessingTask.java:345-453`, `JdbcProcessingTaskRepository.java:114-132`, `ProcessingTaskApplicationService:308-315` |
| C5 | Java pre-claims the worker lease at task creation with hard-coded `worker_dev_001` — task is RUNNING before any worker consumed the message; lease validation nominal; second worker instance breaks | `ProcessingTaskApplicationService:165-290`, `EmbeddingTaskDispatcher.java:134` |

### Important

I6 nonce dedup unimplemented (heartbeat replay extends dead lease / rewinds progress) · I7 `expectedInputVersion` never validated · I8 `/internal/.../artifacts` skips HMAC verify, persists nothing, returns `accepted:true` · I9 embeddings callback skips lease/task-linkage/chunk-ownership checks · I10 `processing_tasks` read-modify-write without row lock/version · I11 callback error→HTTP mapping diverges from SPEC §7 table (HMAC fail → 422 instead of 401; conflicts → generic 409 VERSION_CONFLICT; error envelopes have null requestId/traceId) · I12 `meetings.status` never reaches a terminal value · I13 public write Idempotency-Key decorative (accepted — and on POST /api/meetings even required — but never honored: no replay, no conflict detection) · I14 RAG answer cache: no version in key, lookup before permission check, no STALE invalidation · I15 outbox per-aggregate ordering breaks under retry/multi-instance.

**Assessment: Not production-ready.** Aggregate design is faithful to spec; the five criticals would each surface within hours of real multi-tenant load.

---

## apps/ai-worker (Python) — 4 Critical, 8 Important

### Critical

| # | Finding | Where |
|---|---|---|
| C1 | Every contract-valid `MEETING_FULL_PIPELINE` task deterministically fails at `ALIGNMENT` — registry/validator demand 8 steps, engine implements 6 and throws non-retryable `WORKER_STEP_NOT_IMPLEMENTED`. Unit tests pass only via injected stub engine | `registry.py:10-20`, `audio_pipeline.py:82-101` |
| C2 | No in-flight heartbeats — single fabricated `progress=50` callback before work starts, then silence; real ASR (≤18 min) vs 120s lease TTL guarantees ORPHANED + duplicate execution; a failed pre-work heartbeat kills the whole task | `worker_runtime.py:291-296` |
| C3 | Pika `BlockingConnection` starved during pipeline execution (`asyncio.run` inside delivery callback, heartbeat=30) — broker drops connection, redelivers, duplicates any task >~60s | `rabbitmq_consumer.py:49,71-75` |
| C4 | Unexpected exceptions drop the message with no `/fail` callback (no DLQ = lost); easy to hit via artifact I/O errors or ffprobe `StopIteration` on audio-less files | `worker_runtime.py:301`, `rabbitmq_consumer.py:79-81`, `preprocess.py:104-107` |

### Important

I5 `/internal/rerank` truncates candidates to topN **before** scoring (reranker degenerates to RRF reshuffle) · I6 inbound HMAC verifies path without query string (contract says `URL_PATH_WITH_QUERY`; `warmup?capabilities=all` is unauthenticated input) · I7 no fail-closed on dev-default HMAC/JWT secrets (`ensure_admin_config` is dead code) · I8 CUDA OOM guard `report_oom_and_exit()` has zero call sites; OOM swallowed into generic retryable error against SPEC §8 · I9 `/artifacts` callback never sent; `submit_transcript` passes `artifact_manifest_id=None` · I10 error codes drift from `error-codes.yaml` (a dozen unregistered codes; `AUDIO_FORMAT_UNSUPPORTED` vs canonical `AUDIO_UNSUPPORTED_FORMAT`) · I11 admin BFF: upstream httpx errors → bare 500 without envelope; unguarded `request.json()` · I12 `time.sleep` in async retry path blocks the event loop.

**Assessment: Not production-ready.** The callback/HMAC and validation layers are well built, but the production consume path cannot complete a single contract-valid full-pipeline task; root cause: the production composition was never exercised end-to-end.

---

## apps/meeting-web — 3 Critical, 15 Important

### Critical

| # | Finding | Where |
|---|---|---|
| C1 | Voiceprint enrollment hijacks an arbitrary real meeting (`meetingsList.items[0]`) as upload carrier; Java unconditionally starts `MEETING_FULL_PIPELINE` on every completed audio upload — enrolling a voice silently regenerates someone's meeting transcript/minutes from a 30s clip | `SpeakerEnrollPanel.tsx:22-31` + `AudioUploadApplicationService.java:242` |
| C2 | `sse-reducer` writes **step** status into **task** status: first step SUCCEEDED → page shows 已完成, closes SSE, stops polling, disables cancel while 7 steps still run; `TASK_COMPLETED` hardcodes SUCCEEDED ignoring `PARTIAL_SUCCEEDED` | `sse-reducer.ts:53-57,88-93` |
| C3 | No 401/refresh handling (SPEC §5.2 single-flight refresh unimplemented) and no logout entry point (`useAuth().logout` dead code) — token expiry bricks the session | `client.ts`, `services/auth.ts:44-51` |

### Important

I1 idempotency keys regenerated per HTTP call (retry = duplicate operation; purpose-built `idempotency.ts` dead code) · I2 deleteSpeakerProfile/deleteDocument/logout/ragQuery send no key · I3 transcript list not virtualized/paginated (SPEC mandates) · I4 VERSION_CONFLICT toast claims auto-refresh that never happens (conflict loop) · I5 regenerate-minutes after edit deterministically conflicts (meeting cache never invalidated) · I6 speaker confirm doesn't invalidate transcript · I7 SSE resume uses stale `Last-Event-Id`, zero-backoff reconnect, permanent downgrade after 3 instant failures · I8 upload cancel doesn't abort workers (state resurrected by in-flight `part-start`) · I9 stale-closure part offset (corrupts upload if server adjusts partSizeBytes) · I10 successful upload can render as failed (unguarded post-finalize call) · I11 task retry/cancel errors swallowed · I12 meeting creation bypasses the hook that invalidates the list · I13 exports page uses bare `EventSource` (cannot authenticate; torn down every 3s by poll) · I14 destructive voiceprint actions without confirm dialog · I15 `admin/admin123` prefilled in login form.

**Assessment: Not production-ready.** Hot path works in the happy case; flagship progress page freezes at first completed step, enrollment corrupts unrelated meetings, session expiry bricks the app.

---

## apps/ai-worker-web — 2 Critical, 10 Important

### Critical

| # | Finding | Where |
|---|---|---|
| C1 | Voiceprint can be committed to the wrong person: person radio stays live after the session is bound, but BFF binds `person_id` at session creation and ignores later UI selection — 张三 gets 李四's voiceprint with no hint | `EnrollmentPage.tsx:111-151` + `enrollment.py:71-74,146` |
| C2 | Enrollment dead-ends on session loss (BFF sessions in-process + TTL): create button permanently disabled, every retry hits the dead session, only recovery is full page reload | `EnrollmentPage.tsx:159`, `session_store.py:63,102-106` |

### Important

I1 random per-request idempotency key defeats BFF's session-keyed idempotent commit (retry duplicates speaker profiles) · I2 开始处理 retry after partial failure creates duplicate meetings (non-resumable 4-step chain) · I3 SSE hot-reconnects forever after clean close; page never closes on terminal; lastEventId never updated · I4 detail page never refreshes while `latestTask == null` (outbox-driven task creation window) · I5 no invalidation after commit/create (fresh data hidden 30s; operators redo actions) · I6 export polling: silent timeout, keeps running after navigation · I7 confirming one speaker candidate leaves competing buttons live (racing writes) · I8 silent no-op handlers when `transcriptVersion` missing · I9 whole file buffered + hashed in memory; empty ETag silently accepted (CORS expose-headers gap) · I10 `admin/admin123` prefilled.

**Assessment: Not production-ready.** The SPA's primary job — enrollment — can silently bind biometric data to the wrong person; architecture/auth/sanitization are solid, so this is a fixable interaction layer.

---

## Cross-cutting themes

1. **Idempotency keys**: both SPAs generate a fresh key per HTTP request instead of per user action; the backends' idempotency machinery never engages. Rule going forward: business-key where natural (enrollment session id), action-scoped UUID reused across retries otherwise.
2. **SSE clients**: both SPAs snapshot `lastEventId` once, reconnect without backoff, and mishandle terminal/clean-close.
3. **Production composition untested**: ai-worker's consume path fails at step 3 of 8; Java's RLS failure is masked by superuser test roles. Both need composition-level integration tests with realistic inputs/roles.
4. **Prefilled default credentials** in both login pages.
5. **Stale docs**: root `CLAUDE.md` says BFF `persons.py`/`files.py` are "not yet built" — they are built and registered (`router.py:44-48`). meeting-web `SPEC.md` §10.2 item 9 still demands the removed Phase-K security-level UI.

## Remediation

See [`plans/2026-06-12-review-remediation-index.md`](plans/2026-06-12-review-remediation-index.md) — four plan volumes (P1 ai-worker, P2 meeting-api, P3 meeting-web, P4 ai-worker-web) with locked cross-workspace protocol decisions.
