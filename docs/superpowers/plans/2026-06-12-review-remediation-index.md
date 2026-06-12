# Code Review Remediation — Implementation Plan Index

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement each volume task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Source review:** [`docs/superpowers/2026-06-12-code-review-report.md`](../2026-06-12-code-review-report.md) (full-codebase review on commit `9143010`: 14 Critical, 42 Important across four workspaces)

**Goal:** Close every Critical and Important finding from the 2026-06-12 review so the system survives real multi-tenant load: callbacks that don't block on LLMs, a worker that completes contract-valid pipelines with live heartbeats, a lease lifecycle with no stranded states, and SPAs whose retries are idempotent and whose caches don't lie.

**Architecture:** Four independent volumes (one per workspace) coordinated by the locked protocol decisions below. Backend volumes (P1/P2) unblock the system's core loop; frontend volumes (P3/P4) fix interaction logic and can proceed in parallel.

**Tech Stack:** Java 17 / Spring Boot 3.3 / MyBatis-Plus / Flyway · Python 3.11 / FastAPI / pika · React 18.3 / Vite 5 / TanStack Query · OpenAPI + JSON Schema contracts.

---

## Volumes

| Volume | File | Scope | Findings closed |
|---|---|---|---|
| **P1** | [p1-ai-worker](./2026-06-12-review-remediation-p1-ai-worker.md) | ai-worker production consume path, heartbeats, pika threading, rerank, secrets, error-code registry (incl. contracts edits) | worker C1–C4, I5–I12 |
| **P2** | [p2-meeting-api](./2026-06-12-review-remediation-p2-meeting-api.md) | async LLM phase + tenant context, de-transactionalized external calls, lease model rework, nonce store, callback error mapping, public idempotency, RAG cache, outbox fencing, non-superuser RLS IT, **auth refresh endpoint (new scope, see reconciliation §5)** | api C1–C5, I6–I15 |
| **P3** | [p3-meeting-web](./2026-06-12-review-remediation-p3-meeting-web.md) | enrollment via generic `/files` (no carrier meeting), sse-reducer, 401/refresh + logout, mutation wrapper (idempotency + error surface), invalidation matrix, SSE hardening, upload abort, virtualization | web C1–C3, I1–I15 |
| **P4** | [p4-worker-web](./2026-06-12-review-remediation-p4-worker-web.md) | session-locked person selection + reset path (incl. small BFF guard), session-keyed idempotency, SSE/poll lifecycle, resumable meeting creation, chunked hashing | workstation C1–C2, I1–I10 |

**Recommended implementation order:** P1 ∥ P2 first (system core), then P3 ∥ P4. Within each volume, tasks are dependency-ordered.

---

## Locked cross-workspace decisions

These were fixed before the volumes were drafted; all four volumes are written against them. Do not re-litigate inside a volume — if one proves wrong, update it here and in every affected volume.

1. **Lease protocol** — Java stops pre-claiming leases at task creation (no more `worker_dev_001`); tasks are created QUEUED with null lease. The worker's **first callback** for an attempt claims the lease (owner string stays `{workerId}:{taskId}:{attemptNo}`, sent via existing headers — no wire change). Heartbeats every **20s**, lease TTL **120s** (replacing +5min), progress monotonically non-decreasing. `completeWorkerPhase` clears the lease; the expiry scanner only considers `phase='WORKER_DAG_RUNNING'`; ORPHANED → `requeueOrphaned` (attempt+1, bounded) → republish MQ message; `retry()` republishes; CANCEL_PENDING resolved by scanner once the lease is gone, callbacks on it rejected with a cancel error code.
2. **LLM phase execution** — `WorkerPhaseCompletedListener` goes `@Async` on a dedicated executor; every read/write inside listener + orchestrator wrapped in `TenantScopedTransaction`; LLM/embed/rerank calls live **outside** transactions using the `ExportRenderService` three-phase pattern; a scheduled recovery scanner re-triggers stuck `WORKER_DAG_DONE` tasks via CAS phase transition.
3. **Worker step degradation** — `ALIGNMENT` skips into `skippedSteps` (default-off per SPEC §6.3); `RAG_INDEXING` is implemented through the existing embed runtime if message data suffices, else degrades to `skippedSteps` + `PARTIAL_SUCCEEDED`. The production engine must complete a contract-valid 8-step message either way.
4. **Enrollment audio path** — meeting-web mirrors the BFF orchestration: generic `POST /api/files` → parts → complete → `POST /api/speaker-profiles/{id}/enrollments`. No carrier meetings; enrollment audio never enters the meeting pipeline. Contracts `/files` description amended accordingly.
5. **Idempotency discipline (frontends)** — keys generated per **user action**, not per HTTP call; business key where natural (enrollment commit = session id); keys reused across retries of the same action.
6. **Error-code registry** — every worker-emitted code gets registered in `schemas/common/error-codes.yaml` (single contracts commit, regen all targets); typed callback exceptions on the Java side map per the SPEC §7 table.
7. **No Dramatiq/Prefect migration in this remediation** — the pika loop is fixed in place (worker thread + `process_data_events` + threadsafe ack); SPEC gets a deferral note.
8. **Artifacts callback goes live on both sides** — Java implements `/internal/.../artifacts` persistence with the full validation chain (P2); the worker starts sending it and passes a real `artifact_manifest_id` in transcript submission (P1).

## Post-drafting reconciliation (2026-06-12, after volume investigation)

The volume authors verified every finding against source before planning. These adjustments to the locked decisions are now authoritative:

1. **Decision 3 settled — RAG_INDEXING cannot run worker-side.** Knowledge chunks are created Java-side (`knowledge_chunks`) and shipped to the worker only inside `TEXT_EMBEDDING`/`RAG_REINDEX` task options; the embeddings callback updates existing rows by chunkId. Therefore: Java keeps sending the 8-step message; the worker skips `ALIGNMENT` + `RAG_INDEXING` into `skippedSteps`; **meeting pipelines normally complete `/complete` with `status=PARTIAL_SUCCEEDED`** (sanctioned by ai-worker SPEC.md degradation matrix). Meeting RAG indexing continues to work through the existing Java-driven chunk + `TEXT_EMBEDDING` path. **P2's listener/`completeJavaPhase` must drive SUMMARY/EXTRACTION normally on PARTIAL_SUCCEEDED** with those two steps skipped and no step callbacks for them.
2. **Decision 1 refinement — heartbeat shape.** Worker heartbeats arrive every 20s as `RUNNING` with `progress=1` (stable value, stable per-attempt idempotency key). P2's monotonic-progress guard must suppress only the progress-value regression — **every authenticated heartbeat still extends the lease**, otherwise long steps orphan.
3. **Decision 4 confirmed — no Java MIME work.** `GenericFileUploadApplicationService` already allows `audio/wav`, `audio/mpeg`, `audio/x-m4a`, `audio/flac`. meeting-web mirrors the BFF's `audio/wav` labeling for MediaRecorder webm output; honest webm/mp4 allowlist support is a recorded follow-up, not in scope.
4. **P4's BFF guard needs no contracts change.** `ENROLLMENT_PERSON_MISMATCH` is BFF-local (no `ENROLLMENT_*` codes exist in contracts); commit prefix `fix(worker):`, lands after P1's admin error-envelope task.
5. **New backend gap (found by P3): no refresh endpoint exists.** meeting-web SPEC §5.2 mandates single-flight 401 refresh, but `public-api.yaml` has only login/logout/me and Java has zero refresh/CSRF code. P2 gains a task: contracts + Java `POST /api/auth/refresh` (HttpOnly refresh cookie at login, `XSRF-TOKEN` cookie + `X-CSRF-Token` double-submit on refresh, logout revokes). P3's client is already coded against exactly these names; until P2 ships it, every 401 in meeting-web takes the clean session-expired→`/login` path.
6. **Smaller-than-reported fixes** (kept for accuracy): worker rerank already sorts correctly at `main.py:934-943` — only the pre-truncation at `:918` is wrong. `handlers.lastEventId` in ai-worker-web is re-read per reconnect but never updated nor parsed from `id:` lines — finding stands, fix relocates tracking into the client.
7. **OOM lifecycle**: after an OOM `/fail` the worker acks then exits (137). The task lands FAILED-retryable and re-enters via P2's requeue path — it must not be treated as orphaned.
8. **P2 verification notes** (from drafting): review I13 was partially wrong — `POST /api/meetings` *does* require `Idempotency-Key` (MeetingController.java:51); the keys are accepted but never honored, so the fix is replay/conflict semantics, not header plumbing. `DashScopeLlmGateway` performs DB reads/writes around its HTTP call relying on the caller's transaction — when callers de-transactionalize (C2), the gateway gets its own short TXs so audit writes survive under RLS. `meetings.status` closure is done in the same TX as terminal transition (documented deviation from the "outbox listener" wording — Java-internal, no cross-workspace surface). Cancel protocol: worker callbacks on a CANCEL_PENDING task get **409 `TASK_CANCELLED`** (new contracts code, P1's abort signal); ORPHANED claim attempts get 409 `TASK_LEASE_CONFLICT`; requeue bumps attemptNo with max 3 → FAILED `TASK_RETRY_EXHAUSTED`. Auth refresh specifics: HttpOnly `meeting_refresh_token` cookie (Path=/api/auth, rotated per refresh) + `XSRF-TOKEN` cookie + `X-CSRF-Token` header — names match P3's client exactly.

## Deployment-order constraints (release engineering)

- **P1-C2 (heartbeats) must deploy before or together with P2's lease-TTL change.** Shipping the 120s TTL + requeue scanner against a worker that doesn't heartbeat would orphan-and-requeue every long task in a loop.
- **P2's `/artifacts` persistence should land before P1's worker starts sending it** (otherwise manifests dangle until P2 ships — harmless but untraceable).
- **Contracts changes (error codes, `/files` description, transcript-segment request shape) land as one commit + codegen regen** so all four workspaces' generated types move together; CI's codegen-drift gate enforces this.
- P4 touches `apps/ai-worker/ai_worker/admin/enrollment.py` (person-mismatch guard); P1 touches `admin/java_client.py` + admin routers (error envelopes). Land P1's BFF error-handling task first, then rebase P4's guard on it.

## Run gates per volume

- P1: `cd apps/ai-worker && uv run pyright ai_worker/ && uv run pytest tests/ -x -q`; contracts tasks: `cd packages/meeting-contracts && npm run check && npm run codegen` → `git diff` clean
- P2: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw test` then `./mvnw verify -q` (Docker/Colima; includes the new non-superuser RLS IT); new migrations must pass `psql -v ON_ERROR_STOP=1` locally (ddl-check is a hidden CI gate)
- P3: `cd apps/meeting-web && npx tsc --noEmit && npm test && npm run build`
- P4: `cd apps/ai-worker-web && npm run type-check && npm test && npm run build && npm run e2e`

## Definition of Done

- All Critical and Important findings from the source review closed or explicitly waived with reasoning in the volume doc
- The new composition tests pass: ai-worker end-to-end 8-step pipeline test (P1) and meeting-api non-superuser RLS callback→LLM-phase IT (P2)
- All 7 CI jobs green on each branch; `npm run codegen` leaves `git diff` clean
- `todo.md` section "代码评审修复（2026-06-12）" checked off
- Phase J staging acceptance (`docs/runbooks/phase-j-acceptance.md`) re-run after P1+P2 merge
