# P2 Meeting-API Remediation Plan Index

> **Status:** Planning phase - breaking down into executable sub-tasks
> **Branch:** fix/review-remediation-p2-meeting-api

## Overview

P2 addresses Critical and Important code review findings in the meeting-api Java codebase. The work spans multiple architectural concerns: transaction management, lease lifecycle, callback validation, and authentication.

## Task Breakdown

### Phase 1: Foundation (Must complete first)

**P2.1: Lease Model Rework (C4+C5)**
- Remove pre-claiming (`worker_dev_001`)
- First callback claims lease
- 120s TTL with heartbeat renewal
- Scanner only checks `WORKER_DAG_RUNNING`
- Wire `requeueOrphaned` and `confirmCancelled`
- Row-level locking on callback path

**Dependencies:** None
**Estimated complexity:** High
**Files affected:** 
- `ProcessingTask.java` (domain)
- `ProcessingTaskApplicationService.java` (app)
- `ProcessingTaskCallbackApplicationService.java` (app)
- `ProcessingTaskLeaseScanner.java` (infrastructure)
- Migration for lease TTL change

---

### Phase 2: Transaction & Concurrency (After P2.1)

**P2.2: Async WorkerPhaseCompletedListener (C1+C3)**
- Move to @Async with dedicated executor
- Wrap all listener/orchestrator reads/writes in TenantScopedTransaction
- Add recovery scanner for stuck WORKER_DAG_DONE
- Handle PARTIAL_SUCCEEDED + skippedSteps normally
- Non-superuser RLS integration test

**Dependencies:** P2.1 (lease model must be stable first)
**Estimated complexity:** Medium-High
**Files affected:**
- `WorkerPhaseCompletedListener.java`
- `JavaLlmPhaseOrchestrator.java`
- `TaskStepProgressService.java`
- `WorkerDagDoneRecoveryScanner.java` (new)
- Async executor config

**P2.3: De-transactionalize External Calls (C2)**
- Extract LLM/embed/rerank out of DB transactions
- Apply 3-phase pattern (mark running → call → mark complete)
- Cover: RAG query, minutes generation, extraction

**Dependencies:** P2.2 (orchestrator must be stable)
**Estimated complexity:** Medium
**Files affected:**
- `RagQueryApplicationService.java`
- `MinutesGenerationService.java` (or equivalent)
- `ExtractionService.java` (or equivalent)
- Pattern: follow `ExportRenderService`

---

### Phase 3: Validation & Security (After Phase 2)

**P2.4: Callback Validation Enhancements (I6-I9)**
- Nonce deduplication table
- Progress monotonic guard (block regression, still renew lease)
- expectedInputVersion validation
- /artifacts persistence + full validation chain

**Dependencies:** P2.1, P2.2
**Estimated complexity:** Medium
**Files affected:**
- `CallbackNonceRepository.java` (new)
- `ProcessingTaskCallbackApplicationService.java`
- `ArtifactCallbackApplicationService.java` (new or extend existing)
- Migration for nonce table

**P2.5: Embeddings & Artifacts (I9-I10)**
- Embeddings callback lease/ownership validation
- Artifacts callback real persistence
- Full validation chain

**Dependencies:** P2.4
**Estimated complexity:** Low-Medium
**Files affected:**
- `EmbeddingsCallbackApplicationService.java`
- `ArtifactManifestRepository.java`

---

### Phase 4: Data Integrity (After Phase 3)

**P2.6: Status & Version Tracking (I10-I12)**
- Terminal status writes back to meetings.status
- Public write endpoints enforce Idempotency-Key
- Idempotency replay semantics
- RAG cache keys include version
- RAG permission post-check
- STALE invalidation

**Dependencies:** P2.1-P2.5
**Estimated complexity:** Medium
**Files affected:**
- `ProcessingTaskApplicationService.java`
- `MeetingApplicationService.java`
- `RagAnswerCache.java`
- `RagAuthorizationService.java`
- Idempotency enforcement in controllers

**P2.7: Outbox & Error Handling (I13-I15)**
- Outbox same-aggregate fencing
- Callback error code mapping per SPEC §7
- Response envelope includes requestId/traceId

**Dependencies:** All previous
**Estimated complexity:** Low-Medium
**Files affected:**
- `OutboxPublisher.java`
- `CallbackExceptionHandler.java` (or ControllerAdvice)
- Error mapping configuration

---

### Phase 5: Authentication (Independent)

**P2.8: Auth Refresh Endpoint (New requirement)**
- POST /api/auth/refresh
- HttpOnly refresh cookie (issued on login, revoked on logout)
- XSRF-TOKEN cookie + X-CSRF-Token double-submit
- Contracts update

**Dependencies:** None (independent feature)
**Estimated complexity:** Medium
**Files affected:**
- `openapi/public-api.yaml` (contracts)
- `AuthController.java`
- `AuthApplicationService.java`
- `RefreshTokenRepository.java` (new)
- Migration for refresh tokens
- Security filter chain config

---

## Execution Strategy

1. **Sequential by phase**: Phase 1 → Phase 2 → Phase 3 → Phase 4
2. **P2.8 can run in parallel** with other phases (independent)
3. **Each sub-task gets its own detailed plan** with TDD steps
4. **Commit after each sub-task** completes and passes tests
5. **Run `./mvnw test` after each** sub-task
6. **Run `./mvnw verify -q` at end** of each phase

## Success Criteria

- [ ] All P2.1-P2.8 sub-tasks complete
- [ ] All unit tests pass
- [ ] All integration tests pass (including new RLS test)
- [ ] ArchUnit boundaries preserved
- [ ] No regression in existing functionality
- [ ] Todo.md P2 section marked complete

## Next Steps

1. Create detailed plan for P2.1 (Lease Model Rework)
2. Execute P2.1 via subagent-driven-development
3. Proceed sequentially through remaining sub-tasks
