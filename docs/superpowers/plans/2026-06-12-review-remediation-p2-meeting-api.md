# Meeting API Remediation Plan (Review P2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the five Critical and ten Important meeting-api findings from the 2026-06-12 review: make the Java LLM phase async + tenant-scoped + RLS-safe, get LLM/HTTP calls out of DB transactions, rework the worker-lease lifecycle (no pre-claimed leases, requeue/cancel closure), enforce nonce/expectedInputVersion/artifact/embedding callback validation, close meetings.status, honor public Idempotency-Key, fix RAG answer-cache correctness, fence outbox ordering, and add the missing `POST /api/auth/refresh` endpoint required by meeting-web.

**Architecture:** COLA-V5 six-module Maven build (`start/adapter/app/domain/infrastructure/client`). Adapter does protocol translation only; app owns transactions + tenant context via `TenantScopedTransaction`; domain stays Spring/JDBC-free; infrastructure implements ports with `JdbcTemplate` native SQL + Flyway. Long external calls (DashScope, ai-worker embed/rerank) follow the `ExportRenderService` three-phase pattern: short TX → no-TX external call → short TX persist+outbox. All tenant-table access must run inside `tenantScopedTransaction.execute(...)` because `app.tenant_id` is set with transaction-local `set_config(..., true)` and every table is `FORCE ROW LEVEL SECURITY`.

**Tech Stack:** Java 17 · Spring Boot 3.3 · MyBatis-Plus/JdbcTemplate native SQL · Flyway 10 · RabbitMQ outbox · JUnit 5 + Mockito + AssertJ + ArchUnit + Testcontainers (pgvector/pgvector:pg15) · OpenAPI contracts in `packages/meeting-contracts`.

**Branch:** `fix/review-remediation-p2-meeting-api`
**Source review:** 2026-06-12 four-workspace code review — this volume fixes meeting-api Critical #1–#5 and Important #6–#15.

---

## Review claims adjusted on inspection

Verified every Critical/Important claim against source before planning. Three adjustments:

1. **I13 partially wrong:** `POST /api/meetings` *does* declare `@RequestHeader("Idempotency-Key")` (required) at `MeetingController.java:51` — the header is accepted and mandatory, it is simply never honored (no replay/conflict semantics). `CreateDocumentCommand` and `CreateSpeakerProfileCommand` already carry an `idempotencyKey` field that the app services ignore. Fix scope unchanged: honor the key (replay on same body, 409 on different body).
2. **I8 nuance:** `ArtifactManifestRepository` *does* have one production caller — `DashScopeLlmGateway` persists LLM manifests. The "no callers" claim only holds for the worker-callback path. The `/artifacts` endpoint claim (no HMAC verify, persists nothing, returns `{"accepted": true}`) is verified at `ProcessingTaskCallbackController.java:161-165`.
3. **Error codes missing from contracts:** `TASK_RETRY_EXHAUSTED`, `TASK_CANCELLED`, and `INPUT_VERSION_CONFLICT` are **not** registered in `error-codes.yaml` (checked). Task 1 adds them (contracts sub-task per D2/D5). `CALLBACK_AUTH_FAILED`, `TASK_ATTEMPT_CONFLICT`, `TASK_LEASE_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `TASK_NOT_FOUND` all exist in both `error-codes.yaml` and the hand-written `ErrorCode` enum.

Also verified: ArchUnit does **not** forbid adapter→domain references (`MeetingControllerAdvice` already imports `domain.llm.LlmProviderException` and CI passes), so domain-level conflict exceptions can be mapped in the advice. `meetings` status enum values are `CREATED/PROCESSING/SUCCEEDED/FAILED/DELETED` (no COMPLETED). `processing_tasks` DDL already has `max_attempts` (default 3) and `expected_input_version jsonb` columns; the repository persists neither — D5 reads expected versions from the archived task message instead of widening the aggregate.

## Cross-workspace coordination (locked with P1 worker / P3 meeting-web plans)

- **PARTIAL_SUCCEEDED is the normal worker outcome** for `MEETING_FULL_PIPELINE`: the worker skips `ALIGNMENT` + `RAG_INDEXING` (chunks are Java-side) into `skippedSteps` and calls `/complete` with `status=PARTIAL_SUCCEEDED`. Java keeps sending the 8-step message. Task 3 adds an explicit test that SUMMARY/EXTRACTION still run and the task lands `PARTIAL_SUCCEEDED` terminal.
- **Heartbeats:** every 20s, `RUNNING` with stable `progress=1` and a stable per-attempt idempotency key. The D4 monotonic guard (Task 7) only suppresses the progress write — it must still extend the lease on every authenticated heartbeat.
- **/artifacts:** worker sends `artifactManifestId` in the format `artifact_manifest_{taskId}_{attemptNo}` plus `artifacts[]` per `internal-callback-api.yaml` (`artifactType`, `artifactUri` `^tos://.+`, `sha256`, optional `sizeBytes`/`metadata`). Task 9 persists a manifest row with exactly that id so the later `/transcript` callback can reference it.
- **Worker /fail codes:** P1 registers ~19 new codes (e.g. retryable `WORKER_INTERNAL_ERROR`, GPU-OOM codes). The `/fail` controller does `ErrorCode.valueOf(...)`, so the hand-written Java enum must be re-synced when P1's contracts change lands (Task 1 step 5). OOM exits land the task `FAILED` retryable=true; recovery is the user/ops `retry` endpoint which now republishes (Task 6) — not the orphan loop.
- **CANCEL_PENDING:** callbacks against a CANCEL_PENDING task get HTTP 409 `TASK_CANCELLED` (new code, Task 1) — the worker aborts on it.
- **Auth refresh (P3):** meeting-web is already coded against `POST /api/auth/refresh` with cookie name `XSRF-TOKEN` and header `X-CSRF-Token`. Task 15 matches those exactly.

---

## File Structure

All Java paths relative to `apps/meeting-api/`. Migration timestamps start at `V202606121000`.

**Contracts (`packages/meeting-contracts/`):**
- Modify: `schemas/common/error-codes.yaml` (add TASK_CANCELLED, TASK_RETRY_EXHAUSTED, INPUT_VERSION_CONFLICT)
- Modify: `openapi/public-api.yaml` (add `/auth/refresh`)

**client:**
- Modify: `meeting-api-client/src/main/java/com/meeting/api/client/common/ErrorCode.java`
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/internal/callback/ArtifactsCallbackCommand.java`
- Modify: `meeting-api-client/src/main/java/com/meeting/api/client/meeting/CreateMeetingCommand.java` (add idempotencyKey)
- Modify: `meeting-api-client/src/main/java/com/meeting/api/client/export/CreateExportCommand.java` (add idempotencyKey)
- Modify: `meeting-api-client/src/main/java/com/meeting/api/client/auth/AuthFacade.java`, Create: `client/auth/LoginSessionDTO.java`

**domain:**
- Create: `meeting-api-domain/src/main/java/com/meeting/api/domain/task/TaskAttemptConflictException.java`, `TaskLeaseConflictException.java`, `TaskMessageArchive.java`, `CallbackNonceStore.java`
- Create: `meeting-api-domain/src/main/java/com/meeting/api/domain/idempotency/PublicIdempotencyRepository.java`
- Modify: `meeting-api-domain/src/main/java/com/meeting/api/domain/task/ProcessingTask.java` (claimOrRenewLease, lease clear, requeue/cancel fixes)
- Modify: `meeting-api-domain/src/main/java/com/meeting/api/domain/task/ProcessingTaskRepository.java` (findByIdForUpdate, findStuckInWorkerDagDone, findCancelPendingWithExpiredLease)
- Modify: `meeting-api-domain/src/main/java/com/meeting/api/domain/rag/KnowledgeChunkRepository.java` (findOwners)

**app:**
- Create: `meeting-api-app/src/main/java/com/meeting/api/app/common/CallbackAuthException.java`, `IdempotencyConflictException.java`, `PublicIdempotencyService.java`
- Create: `meeting-api-app/src/main/java/com/meeting/api/app/task/LlmPhaseRecoveryScanner.java`, `ProcessingTaskRequeueService.java`, `CallbackReplayGuard.java`, `MeetingStatusClosureService.java`
- Modify: `app/task/WorkerPhaseCompletedListener.java`, `JavaLlmPhaseOrchestrator.java`, `TaskStepProgressService.java`, `ProcessingTaskCallbackApplicationService.java`, `ProcessingTaskApplicationService.java`, `ProcessingTaskLeaseScanner.java`, `CallbackSecurityVerifier.java`
- Modify: `app/minutes/MinutesApplicationService.java`, `app/extraction/ExtractionApplicationService.java`, `app/rag/RagQueryApplicationService.java`, `app/rag/EmbeddingsCallbackApplicationService.java`, `app/rag/EmbeddingTaskDispatcher.java`, `app/rag/RagAnswerCache.java`, `app/rag/InMemoryRagAnswerCache.java`
- Modify: `app/speaker/SpeakerEnrollmentCallbackApplicationService.java`, `SpeakerCandidatesCallbackApplicationService.java`
- Modify: `app/transcript/TranscriptApplicationService.java`, `app/meeting/MeetingApplicationService.java`, `app/export/ExportApplicationService.java`, `app/document/DocumentApplicationService.java`, `app/speaker/SpeakerProfileApplicationService.java`, `app/auth/InMemoryAuthApplicationService.java`

**adapter:**
- Modify: `meeting-api-adapter/src/main/java/com/meeting/api/adapter/meeting/MeetingControllerAdvice.java`, `adapter/internal/ProcessingTaskCallbackController.java`, `adapter/meeting/MeetingController.java`, `adapter/export/ExportController.java`, `adapter/document/DocumentController.java`, `adapter/speaker/SpeakerProfileController.java`, `adapter/auth/AuthController.java`

**infrastructure:**
- Modify: `meeting-api-infrastructure/src/main/java/com/meeting/api/infrastructure/persistence/task/JdbcProcessingTaskRepository.java`, `infrastructure/mq/OutboxEventStore.java`, `infrastructure/mq/OutboxPublisher.java`, `infrastructure/persistence/rag/JdbcKnowledgeChunkRepository.java`, `infrastructure/gateway/llm/DashScopeLlmGateway.java`
- Create: `infrastructure/mq/OutboxTaskMessageArchive.java`, `infrastructure/persistence/task/JdbcCallbackNonceStore.java`, `infrastructure/persistence/idempotency/JdbcPublicIdempotencyRepository.java`
- Create migrations: `meeting-api-infrastructure/src/main/resources/db/migration/V202606121000__callback_nonces.sql`, `V202606121010__api_idempotency_keys.sql`

**start:**
- Create: `meeting-api-start/src/main/java/com/meeting/api/start/config/AsyncConfig.java`, `LlmPhaseRecoveryScannerConfig.java`, `CallbackNonceCleanupConfig.java`
- Modify: `meeting-api-start/src/main/java/com/meeting/api/start/config/ProcessingTaskLeaseScannerConfig.java`, `meeting-api-start/src/main/resources/application.yml`
- Tests (all in `meeting-api-start/src/test/java/com/meeting/api/`): Create `CallbackErrorMappingTest.java`, `WorkerPhaseListenerTenantScopeTest.java`, `LlmPhaseAsyncWiringTest.java`, `PartialSucceededLlmPhaseTest.java`, `LlmPhaseRecoveryScannerTest.java`, `LlmCallsOutsideTransactionTest.java`, `LeaseClaimOnCallbackTest.java`, `ProcessingTaskRequeueServiceTest.java`, `CallbackReplayGuardTest.java`, `ExpectedInputVersionTest.java`, `ArtifactsCallbackTest.java`, `EmbeddingsCallbackHardeningTest.java`, `MeetingStatusClosureTest.java`, `PublicIdempotencyServiceTest.java`, `RagAnswerCacheVersioningTest.java`, `OutboxPerAggregateFencingTest.java`, `AuthRefreshTest.java`, `CallbackLlmPhaseRlsIT.java`; Modify `ProcessingTaskApplicationServiceTest.java`, `JdbcProcessingTaskRepositoryIT.java`, `MinutesApplicationServiceTest.java`, `ExtractionApplicationServiceTest.java`, `InMemoryRagAnswerCacheTest.java`

**Commands** (run from `apps/meeting-api/` unless noted):
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./mvnw -pl meeting-api-start -am test -Dtest=ClassName   # single test class
./mvnw test                                              # all unit + ArchUnit (no Docker)
./mvnw verify -q                                         # + Testcontainers ITs (needs Docker/Colima)
cd ../../packages/meeting-contracts && npm run check && npm run codegen   # contracts gate
```

---

## Task 1: Contracts — register TASK_CANCELLED / TASK_RETRY_EXHAUSTED / INPUT_VERSION_CONFLICT error codes

Foundation for Tasks 6 (requeue exhaustion, cancel rejection) and 8 (version conflict). Contracts change first, then the hand-written Java enum (the `npm run check` enum-consistency gate verifies Java/TS/Python surfaces against the YAML).

**Files:**
- Modify: `packages/meeting-contracts/schemas/common/error-codes.yaml`
- Modify: `apps/meeting-api/meeting-api-client/src/main/java/com/meeting/api/client/common/ErrorCode.java`

- [ ] **Step 1: Add the three codes to error-codes.yaml** — append to the `# ── Task ──` block (after `TASK_LEASE_CONFLICT` / `WORKER_LEASE_EXPIRED` / `INVALID_TASK_MESSAGE`):

```yaml
  - code: TASK_CANCELLED
    step: TASK
    retryable: false
    userMessage: 任务已请求取消
    i18nKey: errors.TASK_CANCELLED
    opsTags: [task, cancel]
  - code: TASK_RETRY_EXHAUSTED
    step: TASK
    retryable: false
    userMessage: 任务重试次数已用尽
    i18nKey: errors.TASK_RETRY_EXHAUSTED
    opsTags: [task, retry]
```

and to the `# ── Callback ──` block (after `CALLBACK_IDEMPOTENCY_CONFLICT`):

```yaml
  - code: INPUT_VERSION_CONFLICT
    step: CALLBACK
    retryable: false
    userMessage: 任务输入版本与当前数据版本不一致
    i18nKey: errors.INPUT_VERSION_CONFLICT
    opsTags: [callback, version, conflict]
```

- [ ] **Step 2: Add the same constants to the Java enum** — in `ErrorCode.java`, add `TASK_CANCELLED,` and `TASK_RETRY_EXHAUSTED,` directly after `WORKER_LEASE_EXPIRED,` (line 17), and `INPUT_VERSION_CONFLICT,` directly after `CALLBACK_IDEMPOTENCY_CONFLICT,` (line 49).

- [ ] **Step 3: Run the contracts gate + codegen**

```bash
cd packages/meeting-contracts && npm install && npm run check && npm run codegen
git diff --stat   # commit any generated-type drift together with the YAML
```
Expected: `npm run check` passes (enum-consistency sees the new codes on both sides).

- [ ] **Step 4: Compile meeting-api-client**

```bash
cd apps/meeting-api && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -pl meeting-api-client compile -q
```

- [ ] **Step 5 (coordination): P1 worker error codes.** P1 adds ~19 worker `/fail` codes (e.g. `WORKER_INTERNAL_ERROR`, GPU-OOM variants) to the same YAML. `/fail` does `ErrorCode.valueOf(...)`, so after rebasing on P1's contracts commit, re-run `npm run check` — the consistency gate lists any codes missing from `ErrorCode.java`; add them in the same enum blocks. Track this as a rebase checklist item, not new code now.

- [ ] **Step 6: Commit**

```bash
git checkout -b fix/review-remediation-p2-meeting-api
git add packages/meeting-contracts apps/meeting-api/meeting-api-client
git commit -m "fix(api): register TASK_CANCELLED/TASK_RETRY_EXHAUSTED/INPUT_VERSION_CONFLICT error codes"
```

## Task 2: D3 + I11 — typed callback exceptions and SPEC §7 ControllerAdvice mapping

HMAC failure currently maps to 422 `VALIDATION_FAILED` via `IllegalArgumentException`; attempt/lease/idempotency conflicts to 409 `VERSION_CONFLICT` via `IllegalStateException`; callback task-not-found to 422; every advice envelope has `requestId/traceId = null`. SPEC §7 table (verified): `CallbackAuthException→401 CALLBACK_AUTH_FAILED`, `TaskAttemptConflictException→409 TASK_ATTEMPT_CONFLICT`, `TaskLeaseConflictException→409 TASK_LEASE_CONFLICT`, `IdempotencyConflictException→409 IDEMPOTENCY_CONFLICT`.

**Files:**
- Create: `meeting-api-domain/src/main/java/com/meeting/api/domain/task/TaskAttemptConflictException.java`, `TaskLeaseConflictException.java`
- Create: `meeting-api-app/src/main/java/com/meeting/api/app/common/CallbackAuthException.java`, `IdempotencyConflictException.java`
- Modify: `CallbackSecurityVerifier.java`, `ProcessingTask.java` (validateCallback), `ProcessingTaskCallbackApplicationService.java`, `EmbeddingsCallbackApplicationService.java`, `SpeakerEnrollmentCallbackApplicationService.java`, `SpeakerCandidatesCallbackApplicationService.java`, `MeetingControllerAdvice.java`
- Create test: `meeting-api-start/src/test/java/com/meeting/api/CallbackErrorMappingTest.java`

- [ ] **Step 1: Exception classes.** Domain (plain runtime exceptions — domain depends on client only):

```java
package com.meeting.api.domain.task;

public class TaskAttemptConflictException extends RuntimeException {
    public TaskAttemptConflictException(String message) { super(message); }
}
```

```java
package com.meeting.api.domain.task;

public class TaskLeaseConflictException extends RuntimeException {
    public TaskLeaseConflictException(String message) { super(message); }
}
```

App (subclass `ApplicationException` so the existing generic handler carries status):

```java
package com.meeting.api.app.common;

import com.meeting.api.client.common.ErrorCode;

public class CallbackAuthException extends ApplicationException {
    public CallbackAuthException(String message) {
        super(ErrorCode.CALLBACK_AUTH_FAILED, 401, message, false);
    }
}
```

```java
package com.meeting.api.app.common;

import com.meeting.api.client.common.ErrorCode;

public class IdempotencyConflictException extends ApplicationException {
    public IdempotencyConflictException(String message) {
        super(ErrorCode.IDEMPOTENCY_CONFLICT, 409, message, false);
    }
}
```

- [ ] **Step 2: Throw them.**
  - `CallbackSecurityVerifier.verify`: replace all three `new IllegalArgumentException(...)` with `new CallbackAuthException(...)` (missing signature, timestamp skew, signature mismatch). Add import `com.meeting.api.app.common.CallbackAuthException`.
  - `ProcessingTask.validateCallback` (line ~513): attempt mismatch → `throw new TaskAttemptConflictException("callback attempt does not match current attempt");` lease mismatch → `throw new TaskLeaseConflictException("callback lease owner does not match current lease");` (same package, no import).
  - `ProcessingTaskCallbackApplicationService.load` + `EmbeddingsCallbackApplicationService` / `SpeakerEnrollmentCallbackApplicationService` / `SpeakerCandidatesCallbackApplicationService` task-not-found: replace `new IllegalArgumentException("task not found: ...")` with `new ApplicationException(ErrorCode.TASK_NOT_FOUND, 404, "task not found: " + taskId, false)`.
  - Every `throw new IllegalStateException("callback idempotency body hash conflict")` (4 services) → `throw new IdempotencyConflictException("callback idempotency body hash conflict")`. `EmbeddingsCallbackApplicationService` attempt check (line ~75) → `TaskAttemptConflictException`; `SpeakerEnrollmentCallbackApplicationService` attempt/lease checks (lines ~88-93) and `SpeakerCandidatesCallbackApplicationService` (lines ~100-106) → the two domain exceptions.

- [ ] **Step 3: Advice mapping + requestId/traceId stamping.** In `MeetingControllerAdvice` add:

```java
import com.meeting.api.domain.task.TaskAttemptConflictException;
import com.meeting.api.domain.task.TaskLeaseConflictException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExceptionHandler(TaskAttemptConflictException.class)
public ResponseEntity<ApiResponse<Void>> handleTaskAttemptConflict(TaskAttemptConflictException ex) {
    return error(HttpStatus.CONFLICT, ErrorCode.TASK_ATTEMPT_CONFLICT, ex.getMessage(), false, Map.of());
}

@ExceptionHandler(TaskLeaseConflictException.class)
public ResponseEntity<ApiResponse<Void>> handleTaskLeaseConflict(TaskLeaseConflictException ex) {
    return error(HttpStatus.CONFLICT, ErrorCode.TASK_LEASE_CONFLICT, ex.getMessage(), false, Map.of());
}
```

and change `error(...)` to stamp ids from the current request (MDC is not populated in this codebase — verified no `MDC.put` exists):

```java
private ResponseEntity<ApiResponse<Void>> error(HttpStatus status, ErrorCode code, String message, boolean retryable, Map<String, Object> details) {
    ApiResponse<Void> body = new ApiResponse<>(
        false, null, new ErrorInfo(code, message, retryable, details),
        currentHeader("X-Request-Id"), currentHeader("X-Trace-Id")
    );
    return ResponseEntity.status(status).body(body);
}

private static String currentHeader(String name) {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
        return attrs.getRequest().getHeader(name);
    }
    return null;
}
```

(`CallbackAuthException` / `IdempotencyConflictException` ride the existing `@ExceptionHandler(ApplicationException.class)` which honors `ex.httpStatus()` — 401/409/404 land correctly.)

- [ ] **Step 4: Test** — `CallbackErrorMappingTest.java`:

```java
package com.meeting.api;

import com.meeting.api.adapter.meeting.MeetingControllerAdvice;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.CallbackAuthException;
import com.meeting.api.app.common.IdempotencyConflictException;
import com.meeting.api.app.task.CallbackSecurityVerifier;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.domain.task.TaskAttemptConflictException;
import com.meeting.api.domain.task.TaskLeaseConflictException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackErrorMappingTest {
    private final MeetingControllerAdvice advice = new MeetingControllerAdvice();

    @Test
    void hmacFailureThrowsCallbackAuthExceptionMappedTo401() {
        CallbackSecurityVerifier verifier = new CallbackSecurityVerifier("secret", 300, Clock.systemUTC());
        CallbackMetadata badSignature = new CallbackMetadata(
            "worker_a", 1, "worker_a:task_1:1", "PATCH", "req-1", "trace-1",
            OffsetDateTime.now(), "nonce-1", "idem-1", "not-hmac", "/internal/x", "hash");
        assertThatThrownBy(() -> verifier.verify(badSignature)).isInstanceOf(CallbackAuthException.class);

        var response = advice.handleApplication(new CallbackAuthException("bad signature"));
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().error().code()).isEqualTo(ErrorCode.CALLBACK_AUTH_FAILED);
    }

    @Test
    void attemptAndLeaseConflictsMapTo409WithSpecificCodes() {
        assertThat(advice.handleTaskAttemptConflict(new TaskAttemptConflictException("x"))
            .getBody().error().code()).isEqualTo(ErrorCode.TASK_ATTEMPT_CONFLICT);
        assertThat(advice.handleTaskLeaseConflict(new TaskLeaseConflictException("x"))
            .getBody().error().code()).isEqualTo(ErrorCode.TASK_LEASE_CONFLICT);
        assertThat(advice.handleApplication(new IdempotencyConflictException("x"))
            .getStatusCode().value()).isEqualTo(409);
        assertThat(advice.handleApplication(
            new ApplicationException(ErrorCode.TASK_NOT_FOUND, 404, "task not found: t1", false))
            .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void errorEnvelopeStampsRequestAndTraceIdsFromCurrentRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-42");
        request.addHeader("X-Trace-Id", "trace-42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var response = advice.handleTaskLeaseConflict(new TaskLeaseConflictException("x"));
            assertThat(response.getBody().requestId()).isEqualTo("req-42");
            assertThat(response.getBody().traceId()).isEqualTo("trace-42");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }
}
```

- [ ] **Step 5: Run + fix fallout.** `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -pl meeting-api-start -am test -Dtest=CallbackErrorMappingTest` → pass. Then `./mvnw test` — existing tests asserting `IllegalStateException`/`IllegalArgumentException` from callback paths (e.g. `ProcessingTaskCallbackApplicationServiceTest`, `EmbeddingsCallbackApplicationServiceTest`, `SpeakerCandidatesCallbackApplicationServiceTest`) must be updated to the new exception types — adjust assertions only, not behavior.

- [ ] **Step 6: Commit** — `git add -A && git commit -m "fix(api): typed callback exceptions + SPEC §7 advice mapping with requestId/traceId (I11,D3)"`

## Task 3: C1 + C3 (D1) — async LLM-phase listener, tenant-scoped reads, CAS claim, recovery scanner

`WorkerPhaseCompletedListener.onWorkerPhaseCompleted` is `@TransactionalEventListener(AFTER_COMMIT)` with **no** `@Async` (verified: no `@EnableAsync` anywhere) — it runs inside `TransactionTemplate.execute`'s commit processing on the callback request thread (C1). Its `taskRepository.findById` (line 74) and every `load()` in `JavaLlmPhaseOrchestrator` run **outside** `TenantScopedTransaction`, so under non-superuser RLS they return zero rows and SUMMARY/EXTRACTION silently never run (C3 — masked today by superuser test/compose roles).

**Files:**
- Create: `meeting-api-start/src/main/java/com/meeting/api/start/config/AsyncConfig.java`, `LlmPhaseRecoveryScannerConfig.java`
- Create: `meeting-api-app/src/main/java/com/meeting/api/app/task/LlmPhaseRecoveryScanner.java`
- Modify: `WorkerPhaseCompletedListener.java`, `JavaLlmPhaseOrchestrator.java`, `TaskStepProgressService.java`, `ProcessingTaskRepository.java`, `JdbcProcessingTaskRepository.java`
- Create tests: `WorkerPhaseListenerTenantScopeTest.java`, `LlmPhaseAsyncWiringTest.java`, `PartialSucceededLlmPhaseTest.java`, `LlmPhaseRecoveryScannerTest.java`

- [ ] **Step 1: Write failing test — listener reads must run inside the tenant transaction** (`WorkerPhaseListenerTenantScopeTest.java`):

```java
package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.app.task.WorkerPhaseCompletedListener;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPhaseListenerTenantScopeTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-12T10:00:00Z");

    static final class RecordingTenantTx implements TenantScopedTransaction {
        final AtomicInteger depth = new AtomicInteger();
        @Override
        public <T> T execute(String tenantId, String userId, String requestId, Supplier<T> callback) {
            depth.incrementAndGet();
            try { return callback.get(); } finally { depth.decrementAndGet(); }
        }
        @Override
        public void executeWithoutResult(String tenantId, String userId, String requestId, Runnable callback) {
            execute(tenantId, userId, requestId, () -> { callback.run(); return null; });
        }
    }

    static final class DepthCheckingRepo implements ProcessingTaskRepository {
        final RecordingTenantTx tx;
        final Map<String, ProcessingTask> store = new HashMap<>();
        int minObservedDepth = Integer.MAX_VALUE;
        DepthCheckingRepo(RecordingTenantTx tx) { this.tx = tx; }
        @Override public ProcessingTask save(ProcessingTask task) { store.put(task.taskId(), task); return task; }
        @Override public Optional<ProcessingTask> findById(String tenantId, String taskId) {
            minObservedDepth = Math.min(minObservedDepth, tx.depth.get());
            return Optional.ofNullable(store.get(taskId));
        }
        @Override public Optional<ProcessingTask> findLatestByMeetingId(String t, String m) { return Optional.empty(); }
        @Override public List<ExpiredLease> findExpiredLeases(String t, OffsetDateTime n, int l) { return List.of(); }
    }

    @Test
    void holdCheckAndPhaseReadsRunInsideTenantScopedTransaction() {
        RecordingTenantTx tx = new RecordingTenantTx();
        DepthCheckingRepo repo = new DepthCheckingRepo(tx);
        ProcessingTask task = ProcessingTask.create(
            "task_01", "tenant_01", "meeting_01", "SPEAKER_ENROLLMENT",
            List.of(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING), NOW);
        task.enqueue(NOW);
        task.claimLease("worker_a", "worker_a:task_01:1", NOW.plusSeconds(120), NOW);
        task.completeWorkerPhase(ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING),
            List.of(), 1, "worker_a:task_01:1", NOW);
        repo.save(task);

        TaskStepProgressService progress = new TaskStepProgressService(
            repo, tx, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(progress, repo, null, null, tx);

        listener.onWorkerPhaseCompleted(new WorkerPhaseCompletedEvent(
            "evt_01", "tenant_01", "task_01", "SPEAKER_ENROLLMENT", 1,
            ProcessingTaskStatus.SUCCEEDED, List.of(), List.of(), null, 0, NOW));

        assertThat(repo.minObservedDepth)
            .as("every repository read in the listener path must run inside tenantScopedTransaction")
            .isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 2: Run it — expect compile failure** (`WorkerPhaseCompletedListener` has no 5-arg constructor):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -pl meeting-api-start -am test -Dtest=WorkerPhaseListenerTenantScopeTest
```

- [ ] **Step 3: Implement listener changes.** In `WorkerPhaseCompletedListener`: add field `private final TenantScopedTransaction tenantScopedTransaction;` (import `com.meeting.api.app.common.TenantScopedTransaction`); change the `@Autowired` constructor to take it as 5th parameter; legacy convenience constructors delegate with `TenantScopedTransaction.immediate()`:

```java
public WorkerPhaseCompletedListener(TaskStepProgressService taskStepProgressService, ProcessingTaskRepository taskRepository) {
    this(taskStepProgressService, taskRepository, null, null, TenantScopedTransaction.immediate());
}
public WorkerPhaseCompletedListener(TaskStepProgressService taskStepProgressService, ProcessingTaskRepository taskRepository, JavaLlmPhaseOrchestrator javaLlmPhaseOrchestrator) {
    this(taskStepProgressService, taskRepository, javaLlmPhaseOrchestrator, null, TenantScopedTransaction.immediate());
}
```

Replace the hold-check read (lines 74-81) with a tenant-scoped read:

```java
boolean held = tenantScopedTransaction.execute(event.tenantId(), null, null, () ->
    taskRepository.findById(event.tenantId(), event.taskId())
        .map(ProcessingTask::holdAtWorkerPhase)
        .orElse(false));
if (held) {
    log.info("worker_phase_completed_held task={} tenant={} waiting_for_resume", event.taskId(), event.tenantId());
    return;
}
```

Also fix the javadoc (it currently claims "Worker callback responses are not blocked by this listener" — false until this task; rewrite to describe the async executor). In `JavaLlmPhaseOrchestrator`: add `TenantScopedTransaction` as 5th constructor parameter (keep a 4-arg convenience constructor delegating with `TenantScopedTransaction.immediate()` for existing tests) and wrap `load`:

```java
private ProcessingTask load(String tenantId, String taskId) {
    return tenantScopedTransaction.execute(tenantId, null, null, () -> taskRepository.findById(tenantId, taskId))
        .orElseThrow(() -> new ApplicationException(ErrorCode.TASK_NOT_FOUND, 404, "task not found: " + taskId, false));
}
```

- [ ] **Step 4: Run Step 1 test — expect pass.** Also run `-Dtest=WorkerPhaseCompletedListenerTest` (legacy ctor still compiles).

- [ ] **Step 5: Write failing test — async wiring** (`LlmPhaseAsyncWiringTest.java`):

```java
package com.meeting.api;

import com.meeting.api.app.task.WorkerPhaseCompletedListener;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import com.meeting.api.start.config.AsyncConfig;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPhaseAsyncWiringTest {
    @Test
    void listenerMethodIsAsyncOnLlmPhaseExecutor() throws Exception {
        Method m = WorkerPhaseCompletedListener.class.getMethod("onWorkerPhaseCompleted", WorkerPhaseCompletedEvent.class);
        Async async = m.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("llmPhaseExecutor");
    }

    @Test
    void asyncConfigEnablesAsyncWithBoundedExecutor() {
        assertThat(AsyncConfig.class.getAnnotation(EnableAsync.class)).isNotNull();
        ThreadPoolTaskExecutor executor = new AsyncConfig().llmPhaseExecutor();
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        assertThat(executor.getQueueCapacity()).isEqualTo(100);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("llm-phase-");
    }
}
```

- [ ] **Step 6: Run — expect compile failure** (no `AsyncConfig`). Then implement `AsyncConfig.java`:

```java
package com.meeting.api.start.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor backing the worker-complete → Java-LLM-phase hand-off (review C1 / D1).
 * CallerRunsPolicy: under saturation we degrade to the caller thread instead of
 * dropping an LLM phase — the recovery scanner is the second line of defense.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {
    @Bean(name = "llmPhaseExecutor")
    public ThreadPoolTaskExecutor llmPhaseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("llm-phase-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

and annotate the listener method (order matters only for readability; `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` is a supported combination — the event fires after commit and is dispatched onto the executor):

```java
@Async("llmPhaseExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void onWorkerPhaseCompleted(WorkerPhaseCompletedEvent event) {
```

(import `org.springframework.scheduling.annotation.Async`; `meeting-api-app` already has `spring-context` — same dependency that provides `@TransactionalEventListener`.)

- [ ] **Step 7: Run Step 5 test — expect pass. Commit:**

```bash
git add -A && git commit -m "fix(api): run Java LLM phase async with tenant-scoped reads (C1,C3,D1)"
```

- [ ] **Step 8: Write failing test — CAS claim of the LLM phase** (add to new `LlmPhaseRecoveryScannerTest.java`, which also hosts the scanner tests below):

```java
package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.JavaLlmPhaseOrchestrator;
import com.meeting.api.app.task.LlmPhaseRecoveryScanner;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPhaseRecoveryScannerTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    static final class InMemoryRepo implements ProcessingTaskRepository {
        final Map<String, ProcessingTask> store = new HashMap<>();
        @Override public ProcessingTask save(ProcessingTask t) { store.put(t.taskId(), t); return t; }
        @Override public Optional<ProcessingTask> findById(String tenant, String id) { return Optional.ofNullable(store.get(id)); }
        @Override public Optional<ProcessingTask> findLatestByMeetingId(String t, String m) { return Optional.empty(); }
        @Override public List<ExpiredLease> findExpiredLeases(String t, OffsetDateTime n, int l) { return List.of(); }
        @Override public List<String> findStuckInWorkerDagDone(String tenant, OffsetDateTime olderThan, int limit) {
            return store.values().stream()
                .filter(t -> t.phase() == ProcessingTaskPhase.WORKER_DAG_DONE && !t.holdAtWorkerPhase())
                .filter(t -> t.updatedAt().isBefore(olderThan))
                .map(ProcessingTask::taskId)
                .limit(limit)
                .toList();
        }
    }

    private static ProcessingTask workerDagDoneFullPipeline(OffsetDateTime at) {
        ProcessingTask task = ProcessingTask.create("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE",
            List.of(ProcessingStep.AUDIO_UPLOAD, ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR,
                ProcessingStep.ALIGNMENT, ProcessingStep.DIARIZATION, ProcessingStep.SPEAKER_EMBEDDING,
                ProcessingStep.SPEAKER_MATCHING, ProcessingStep.TRANSCRIPT_MERGE, ProcessingStep.RAG_INDEXING,
                ProcessingStep.SUMMARY, ProcessingStep.EXTRACTION), at);
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, at);
        task.enqueue(at);
        task.claimLease("worker_a", "worker_a:task_01:1", at.plusSeconds(120), at);
        task.completeWorkerPhase(ProcessingTaskStatus.PARTIAL_SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.DIARIZATION,
                ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING, ProcessingStep.TRANSCRIPT_MERGE),
            List.of(new com.meeting.api.domain.task.WorkerPhaseCompletedEvent.SkippedStep(ProcessingStep.ALIGNMENT, "worker does not align"),
                new com.meeting.api.domain.task.WorkerPhaseCompletedEvent.SkippedStep(ProcessingStep.RAG_INDEXING, "chunks are java-side")),
            1, "worker_a:task_01:1", at);
        return task;
    }

    @Test
    void tryBeginJavaPhaseClaimsExactlyOnce() {
        InMemoryRepo repo = new InMemoryRepo();
        repo.save(workerDagDoneFullPipeline(NOW.minusMinutes(10)));
        TaskStepProgressService progress = new TaskStepProgressService(repo, TenantScopedTransaction.immediate(), CLOCK);

        assertThat(progress.tryBeginJavaPhase("tenant_01", "task_01")).isTrue();
        assertThat(progress.tryBeginJavaPhase("tenant_01", "task_01")).isFalse();
        assertThat(repo.store.get("task_01").phase()).isEqualTo(ProcessingTaskPhase.JAVA_LLM_RUNNING);
    }
}
```

- [ ] **Step 9: Run — expect compile failure** (`findStuckInWorkerDagDone`, `tryBeginJavaPhase` don't exist). Implement:

`ProcessingTaskRepository` (domain) — add default methods so in-memory test repos keep compiling:

```java
/** Row-locked load for read-modify-write callback paths (SPEC §7 isolation table). */
default Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
    return findById(tenantId, taskId);
}

/** Tasks stuck at WORKER_DAG_DONE (hold flag excluded) older than the threshold — D1 recovery scanner. */
default List<String> findStuckInWorkerDagDone(String tenantId, OffsetDateTime olderThan, int limit) {
    return List.of();
}
```

`JdbcProcessingTaskRepository` — real implementations:

```java
@Override
public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
    List<String> locked = jdbcTemplate.query(
        "SELECT id FROM processing_tasks WHERE tenant_id = ? AND id = ? FOR UPDATE",
        (rs, rowNum) -> rs.getString("id"), tenantId, taskId);
    if (locked.isEmpty()) {
        return Optional.empty();
    }
    return findById(tenantId, taskId);
}

@Override
public List<String> findStuckInWorkerDagDone(String tenantId, OffsetDateTime olderThan, int limit) {
    return jdbcTemplate.query(
        """
        SELECT id
          FROM processing_tasks
         WHERE tenant_id = ?
           AND status = 'RUNNING'
           AND phase = 'WORKER_DAG_DONE'
           AND hold_at_worker_phase = false
           AND updated_at < ?
         ORDER BY updated_at ASC
         LIMIT ?
        """,
        (rs, rowNum) -> rs.getString("id"),
        tenantId, Timestamp.from(olderThan.toInstant()), limit);
}
```

`TaskStepProgressService` — CAS claim:

```java
/**
 * CAS-style WORKER_DAG_DONE → JAVA_LLM_RUNNING transition (D1). Row-locked so a
 * concurrent listener / recovery-scanner / resume trigger claims at most once.
 */
public boolean tryBeginJavaPhase(String tenantId, String taskId) {
    return tenantScopedTransaction.execute(tenantId, null, null, () -> {
        ProcessingTask task = taskRepository.findByIdForUpdate(tenantId, taskId)
            .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (task.phase() != ProcessingTaskPhase.WORKER_DAG_DONE) {
            return false;
        }
        task.beginJavaLlm(OffsetDateTime.now(clock));
        taskRepository.save(task);
        return true;
    });
}
```

`JavaLlmPhaseOrchestrator.run` — use the CAS instead of unconditional `beginJavaPhase`:

```java
public ProcessingTaskDTO run(String tenantId, String taskId) {
    ProcessingTask task = load(tenantId, taskId);
    if (task.phase() == ProcessingTaskPhase.TERMINAL) {
        return ProcessingTaskAssembler.toDto(task);
    }
    if (task.phase() == ProcessingTaskPhase.WORKER_DAG_DONE) {
        if (!taskStepProgressService.tryBeginJavaPhase(tenantId, taskId)) {
            // Another trigger (listener, recovery scanner, resume-java-phase) claimed it.
            return ProcessingTaskAssembler.toDto(load(tenantId, taskId));
        }
        task = load(tenantId, taskId);
    }
    // ... unchanged from here (JAVA_LLM_RUNNING guard, meeting-bound guard, runSummary/runExtraction, completeJavaPhase)
}
```

- [ ] **Step 10: Run Step 8 test — expect pass.**

- [ ] **Step 11: Write test — PARTIAL_SUCCEEDED with skipped ALIGNMENT/RAG_INDEXING is the normal path** (P1 coordination; `PartialSucceededLlmPhaseTest.java`). Reuses `LlmPhaseRecoveryScannerTest.InMemoryRepo` + `workerDagDoneFullPipeline` (move both into this file or a shared package-private fixture class — implementer's choice, keep one definition):

```java
package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.extraction.ExtractionApplicationService;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.app.task.JavaLlmPhaseOrchestrator;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.domain.task.ProcessingTask;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PartialSucceededLlmPhaseTest {

    @Test
    void partialSucceededWorkerOutcomeStillRunsSummaryAndExtraction() {
        var repo = new LlmPhaseRecoveryScannerTest.InMemoryRepo();
        var now = java.time.OffsetDateTime.parse("2026-06-12T10:00:00Z");
        repo.save(LlmPhaseRecoveryScannerTest.workerDagDoneFullPipeline(now.minusMinutes(1)));
        var progress = new TaskStepProgressService(repo, TenantScopedTransaction.immediate(),
            Clock.fixed(now.toInstant(), ZoneOffset.UTC));
        MinutesApplicationService minutes = mock(MinutesApplicationService.class);
        ExtractionApplicationService extraction = mock(ExtractionApplicationService.class);
        var orchestrator = new JavaLlmPhaseOrchestrator(progress, repo, minutes, extraction,
            TenantScopedTransaction.immediate());

        orchestrator.run("tenant_01", "task_01");

        verify(minutes).generateForTask("tenant_01", "meeting_01", "task_01", null);
        verify(extraction).extractForTask("tenant_01", "meeting_01", "task_01");
        ProcessingTask task = repo.store.get("task_01");
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.PARTIAL_SUCCEEDED); // skipped steps keep it PARTIAL
        assertThat(task.step(ProcessingStep.SUMMARY).status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(task.step(ProcessingStep.EXTRACTION).status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(task.step(ProcessingStep.ALIGNMENT).status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(task.step(ProcessingStep.RAG_INDEXING).status()).isEqualTo(StepStatus.SKIPPED);
    }
}
```

(Make `InMemoryRepo` and `workerDagDoneFullPipeline` package-visible `static` members for reuse.) Run — expect pass with the Step 9 implementation; if it fails, fix before continuing — this is the P1 normal-path contract.

- [ ] **Step 12: Write failing test — recovery scanner** (append to `LlmPhaseRecoveryScannerTest`):

```java
    @Test
    void scannerRetriggersStuckWorkerDagDoneTasksIdempotently() {
        InMemoryRepo repo = new InMemoryRepo();
        repo.save(workerDagDoneFullPipeline(NOW.minusMinutes(10)));   // stuck > 5min
        TaskStepProgressService progress = new TaskStepProgressService(repo, TenantScopedTransaction.immediate(), CLOCK);
        var minutes = org.mockito.Mockito.mock(com.meeting.api.app.minutes.MinutesApplicationService.class);
        var extraction = org.mockito.Mockito.mock(com.meeting.api.app.extraction.ExtractionApplicationService.class);
        JavaLlmPhaseOrchestrator orchestrator = new JavaLlmPhaseOrchestrator(
            progress, repo, minutes, extraction, TenantScopedTransaction.immediate());
        LlmPhaseRecoveryScanner scanner = new LlmPhaseRecoveryScanner(
            repo, TenantScopedTransaction.immediate(), orchestrator, CLOCK, Duration.ofMinutes(5), 50);

        LlmPhaseRecoveryScanner.ScanReport first = scanner.scanOnce(List.of("tenant_01"));
        LlmPhaseRecoveryScanner.ScanReport second = scanner.scanOnce(List.of("tenant_01"));

        assertThat(first.recovered()).isEqualTo(1);
        assertThat(second.scanned()).isZero();   // terminal now — no longer stuck
        assertThat(repo.store.get("task_01").phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
    }
```

- [ ] **Step 13: Implement `LlmPhaseRecoveryScanner`** (`meeting-api-app/.../app/task/`):

```java
package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D1 backstop: re-triggers the Java LLM phase for tasks stuck at
 * WORKER_DAG_DONE longer than {@code stuckThreshold} (e.g. async executor
 * lost the hand-off, process restart between callback commit and listener).
 * Safe to double-fire: {@link TaskStepProgressService#tryBeginJavaPhase}
 * is a row-locked CAS, so only one trigger wins.
 */
public class LlmPhaseRecoveryScanner {
    private static final Logger LOG = LoggerFactory.getLogger(LlmPhaseRecoveryScanner.class);

    private final ProcessingTaskRepository taskRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final JavaLlmPhaseOrchestrator orchestrator;
    private final Clock clock;
    private final Duration stuckThreshold;
    private final int batchSize;

    public LlmPhaseRecoveryScanner(
        ProcessingTaskRepository taskRepository,
        TenantScopedTransaction tenantScopedTransaction,
        JavaLlmPhaseOrchestrator orchestrator,
        Clock clock,
        Duration stuckThreshold,
        int batchSize
    ) {
        this.taskRepository = taskRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.orchestrator = orchestrator;
        this.clock = clock;
        this.stuckThreshold = stuckThreshold;
        this.batchSize = batchSize;
    }

    public ScanReport scanOnce(List<String> tenantIds) {
        OffsetDateTime olderThan = OffsetDateTime.now(clock).minus(stuckThreshold);
        int scanned = 0;
        int recovered = 0;
        for (String tenantId : tenantIds) {
            List<String> stuck = tenantScopedTransaction.execute(tenantId, "llm-phase-recovery", null,
                () -> taskRepository.findStuckInWorkerDagDone(tenantId, olderThan, batchSize));
            scanned += stuck.size();
            for (String taskId : stuck) {
                try {
                    orchestrator.run(tenantId, taskId);
                    recovered++;
                    LOG.info("llm_phase_recovered task={} tenant={}", taskId, tenantId);
                } catch (RuntimeException ex) {
                    LOG.warn("llm_phase_recovery_failed task={} tenant={} reason={}", taskId, tenantId, ex.getMessage(), ex);
                }
            }
        }
        return new ScanReport(scanned, recovered);
    }

    public record ScanReport(int scanned, int recovered) {}
}
```

and `LlmPhaseRecoveryScannerConfig` (start, mirrors `ProcessingTaskLeaseScannerConfig`):

```java
package com.meeting.api.start.config;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.JavaLlmPhaseOrchestrator;
import com.meeting.api.app.task.LlmPhaseRecoveryScanner;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@ConditionalOnProperty(prefix = "meeting.llm-phase-recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LlmPhaseRecoveryScannerConfig {
    private static final Logger LOG = LoggerFactory.getLogger(LlmPhaseRecoveryScannerConfig.class);

    private final LlmPhaseRecoveryScanner scanner;
    private final List<String> tenantIds;

    public LlmPhaseRecoveryScannerConfig(
        ProcessingTaskRepository taskRepository,
        TenantScopedTransaction tenantScopedTransaction,
        JavaLlmPhaseOrchestrator orchestrator,
        @Value("${meeting.llm-phase-recovery.stuck-threshold-seconds:300}") long stuckThresholdSeconds,
        @Value("${meeting.llm-phase-recovery.batch-size:50}") int batchSize,
        @Value("${meeting.tenants.active:tenant_default}") String tenantIdsCsv
    ) {
        this.scanner = new LlmPhaseRecoveryScanner(
            taskRepository, tenantScopedTransaction, orchestrator,
            Clock.systemUTC(), Duration.ofSeconds(stuckThresholdSeconds), batchSize);
        this.tenantIds = ActiveTenantList.parse(tenantIdsCsv);
    }

    @Bean
    public LlmPhaseRecoveryScanner llmPhaseRecoveryScanner() {
        return scanner;
    }

    @Scheduled(fixedDelayString = "${meeting.llm-phase-recovery.interval-ms:60000}", initialDelayString = "${meeting.llm-phase-recovery.initial-delay-ms:60000}")
    public void scanStuckLlmPhases() {
        if (tenantIds.isEmpty()) return;
        try {
            LlmPhaseRecoveryScanner.ScanReport report = scanner.scanOnce(tenantIds);
            if (report.scanned() > 0) {
                LOG.info("llm_phase_recovery_run scanned={} recovered={}", report.scanned(), report.recovered());
            }
        } catch (RuntimeException cause) {
            LOG.warn("llm_phase_recovery_run_failed", cause);
        }
    }
}
```

- [ ] **Step 14: Run all four new test classes + full module** — `./mvnw -pl meeting-api-start -am test -Dtest='WorkerPhaseListenerTenantScopeTest,LlmPhaseAsyncWiringTest,PartialSucceededLlmPhaseTest,LlmPhaseRecoveryScannerTest'` then `./mvnw test` (ArchUnit must stay green — no new layer violations: app gained no infra imports, start config only wires app beans).

- [ ] **Step 15: Commit** — `git add -A && git commit -m "fix(api): CAS LLM-phase claim + WORKER_DAG_DONE recovery scanner + PARTIAL_SUCCEEDED path (C1,C3,D1)"`

## Task 4: C2 (D1) — move LLM / ai-worker HTTP calls out of DB transactions

`RagQueryApplicationService.query` wraps embed (HTTP) + rerank (HTTP) + DashScope (p95 ~6s) in one `tenantScopedTransaction.execute` (Hikari pool default 20); `MinutesApplicationService.doRegenerate` and `ExtractionApplicationService.doExtract` do the same for their DashScope call. Verified. Constraint discovered on inspection: `DashScopeLlmGateway.complete` itself does DB reads (prompt-template lookup) **before** and DB writes (`artifact_manifests`, `llm_call_logs`) **after** the HTTP call — today it relies on the caller's ambient tenant TX. De-transactionalizing the callers without fixing the gateway would break those writes under real RLS, so the gateway gets its own short TXs (infrastructure may depend on app per ArchUnit rule 4 — `TenantTransactionTemplate` precedent).

**Files:**
- Modify: `app/minutes/MinutesApplicationService.java`, `app/extraction/ExtractionApplicationService.java`, `app/rag/RagQueryApplicationService.java`, `infrastructure/gateway/llm/DashScopeLlmGateway.java`
- Create test: `meeting-api-start/src/test/java/com/meeting/api/LlmCallsOutsideTransactionTest.java`
- Modify tests: `DashScopeLlmGatewayTest.java` (constructor gains a `TenantScopedTransaction` — pass `TenantScopedTransaction.immediate()`)

- [ ] **Step 1: Write failing test** (`LlmCallsOutsideTransactionTest.java`) — a depth-recording `TenantScopedTransaction` proves gateways are invoked at depth 0 and repositories at depth ≥ 1:

```java
package com.meeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.extraction.ExtractionApplicationService;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.app.rag.RagAnswerCache;
import com.meeting.api.app.rag.RagAuthorizationService;
import com.meeting.api.app.rag.RagQueryApplicationService;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.rag.RagQueryCommand;
import com.meeting.api.client.rag.RagQueryScope;
import com.meeting.api.domain.extraction.ActionItemRepository;
import com.meeting.api.domain.extraction.DecisionRepository;
import com.meeting.api.domain.extraction.RiskRepository;
import com.meeting.api.domain.llm.LlmGateway;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.minutes.MinutesRepository;
import com.meeting.api.domain.rag.EmbeddingGateway;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository.RetrievalScope;
import com.meeting.api.domain.rag.RagCitationEnricher;
import com.meeting.api.domain.rag.RerankGateway;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmCallsOutsideTransactionTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    static final class TxRecorder implements TenantScopedTransaction {
        final AtomicInteger depth = new AtomicInteger();
        @Override public <T> T execute(String tenantId, String userId, String requestId, Supplier<T> callback) {
            depth.incrementAndGet();
            try { return callback.get(); } finally { depth.decrementAndGet(); }
        }
        @Override public void executeWithoutResult(String tenantId, String userId, String requestId, Runnable callback) {
            execute(tenantId, userId, requestId, () -> { callback.run(); return null; });
        }
    }

    private static Meeting meeting() {
        return new Meeting.Builder()
            .id("meeting_01").tenantId("tenant_01").title("M")
            .status(MeetingStatus.PROCESSING).language("zh")
            .transcriptVersion(1).minutesVersion(0)
            .createdAt(NOW).createdBy("user_01").participants(List.of())
            .build();
    }

    @Test
    void minutesGenerationCallsLlmOutsideTenantTransaction() {
        TxRecorder tx = new TxRecorder();
        MeetingRepository meetings = mock(MeetingRepository.class);
        when(meetings.findById("tenant_01", "meeting_01")).thenReturn(Optional.of(meeting()));
        TranscriptRepository transcripts = mock(TranscriptRepository.class);
        when(transcripts.currentTranscriptVersion("tenant_01", "meeting_01")).thenReturn(1);
        when(transcripts.findByMeeting("tenant_01", "meeting_01", 1)).thenReturn(List.of());
        MinutesRepository minutesRepo = mock(MinutesRepository.class);
        when(minutesRepo.currentMinutesVersion("tenant_01", "meeting_01")).thenReturn(0);
        AtomicInteger depthAtLlm = new AtomicInteger(-1);
        AtomicInteger depthAtSave = new AtomicInteger(-1);
        when(minutesRepo.save(any())).thenAnswer(inv -> { depthAtSave.set(tx.depth.get()); return null; });
        LlmGateway llm = request -> {
            depthAtLlm.set(tx.depth.get());
            return new LlmGateway.LlmResponse(
                "{\"title\":\"T\",\"markdown\":\"# m\",\"sections\":[]}", null, 1, 1, 1L, "qwen", "log_1", "art_1");
        };
        MinutesApplicationService service = new MinutesApplicationService(
            meetings, minutesRepo, transcripts, llm, tx, new ObjectMapper(), CLOCK);

        service.generateForTask("tenant_01", "meeting_01", "task_01", null);

        assertThat(depthAtLlm.get()).as("LLM call must be outside any tenant TX").isZero();
        assertThat(depthAtSave.get()).as("minutes persistence must be inside a tenant TX").isGreaterThanOrEqualTo(1);
    }

    @Test
    void extractionCallsLlmOutsideTenantTransaction() {
        TxRecorder tx = new TxRecorder();
        MeetingRepository meetings = mock(MeetingRepository.class);
        when(meetings.findById("tenant_01", "meeting_01")).thenReturn(Optional.of(meeting()));
        TranscriptRepository transcripts = mock(TranscriptRepository.class);
        when(transcripts.currentTranscriptVersion("tenant_01", "meeting_01")).thenReturn(1);
        when(transcripts.findByMeeting("tenant_01", "meeting_01", 1)).thenReturn(List.of());
        AtomicInteger depthAtLlm = new AtomicInteger(-1);
        LlmGateway llm = request -> {
            depthAtLlm.set(tx.depth.get());
            return new LlmGateway.LlmResponse(
                "{\"actionItems\":[],\"decisions\":[],\"risks\":[]}", null, 1, 1, 1L, "qwen", "log_1", "art_1");
        };
        ExtractionApplicationService service = new ExtractionApplicationService(
            meetings, transcripts,
            mock(ActionItemRepository.class), mock(DecisionRepository.class), mock(RiskRepository.class),
            llm, tx, new ObjectMapper(), CLOCK);

        service.extractForTask("tenant_01", "meeting_01", "task_01");

        assertThat(depthAtLlm.get()).isZero();
    }

    @Test
    void ragQueryEmbedsOutsideTenantTxAndRetrievesInsideShortTx() {
        TxRecorder tx = new TxRecorder();
        RagAuthorizationService authz = mock(RagAuthorizationService.class);
        AtomicInteger depthAtAuthorize = new AtomicInteger(-1);
        when(authz.authorizeScope(anyString(), anyString(), any())).thenAnswer(inv -> {
            depthAtAuthorize.set(tx.depth.get());
            return RetrievalScope.EMPTY;
        });
        AtomicInteger depthAtEmbed = new AtomicInteger(-1);
        EmbeddingGateway embed = request -> {
            depthAtEmbed.set(tx.depth.get());
            return new EmbeddingGateway.EmbedResult("bge-m3-v1", 1, List.of(new float[] {0.1f}));
        };
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        AtomicInteger depthAtSearch = new AtomicInteger(-1);
        when(chunks.searchByVector(anyString(), any(), any(), anyInt())).thenAnswer(inv -> {
            depthAtSearch.set(tx.depth.get());
            return List.of();
        });
        when(chunks.searchByKeyword(anyString(), anyString(), any(), anyInt())).thenReturn(List.of());
        RagAnswerCache cache = mock(RagAnswerCache.class);
        when(cache.lookup(any())).thenReturn(Optional.empty());
        RagQueryApplicationService service = new RagQueryApplicationService(
            tx, authz, embed, chunks,
            mock(RerankGateway.class), mock(LlmGateway.class), mock(RagCitationEnricher.class),
            cache, new ObjectMapper(), 50, 50, 60);

        service.query(new RagQueryCommand(
            "tenant_01", "user_01", "what was decided?", RagQueryScope.EMPTY, 5, false, "req_1", "trace_1"));

        assertThat(depthAtEmbed.get()).as("embed HTTP call outside TX").isZero();
        assertThat(depthAtAuthorize.get()).as("authorize runs in a short TX").isGreaterThanOrEqualTo(1);
        assertThat(depthAtSearch.get()).as("retrieval runs in a short TX").isGreaterThanOrEqualTo(1);
    }
}
```

- [ ] **Step 2: Run — expect assertion failures** (`depthAtLlm` / `depthAtEmbed` is 1, not 0):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -pl meeting-api-start -am test -Dtest=LlmCallsOutsideTransactionTest
```

- [ ] **Step 3: Restructure `MinutesApplicationService`.** `regenerate(...)` and `generateForTask(...)` stop wrapping `doRegenerate` in a transaction — `doRegenerate` now manages its own three phases:

```java
@Override
public MinutesDTO regenerate(RegenerateMinutesCommand command) {
    return doRegenerate(command, null);
}

public MinutesDTO generateForTask(String tenantId, String meetingId, String taskId, Integer expectedTranscriptVersion) {
    RegenerateMinutesCommand command = new RegenerateMinutesCommand(
        tenantId, meetingId, null, null, null, expectedTranscriptVersion, null);
    return doRegenerate(command, taskId);
}

private MinutesDTO doRegenerate(RegenerateMinutesCommand command, String taskId) {
    // Short TX #1 — load meeting, version-gate, snapshot transcript + workstation context.
    GenerationInput input = tenantScopedTransaction.execute(
        command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            Meeting meeting = meetingRepository.findById(command.tenantId(), command.meetingId())
                .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + command.meetingId()));
            int currentTranscriptVersion = transcriptRepository.currentTranscriptVersion(command.tenantId(), command.meetingId());
            if (command.expectedTranscriptVersion() != null && command.expectedTranscriptVersion() != currentTranscriptVersion) {
                throw new VersionConflictException("transcript version mismatch: expected="
                    + command.expectedTranscriptVersion() + " actual=" + currentTranscriptVersion);
            }
            int currentMinutesVersion = minutesRepository.currentMinutesVersion(command.tenantId(), command.meetingId());
            if (command.expectedMinutesVersion() != null && command.expectedMinutesVersion() != currentMinutesVersion) {
                throw new VersionConflictException("minutes version mismatch: expected="
                    + command.expectedMinutesVersion() + " actual=" + currentMinutesVersion);
            }
            List<TranscriptRepository.TranscriptSegmentRecord> segments = transcriptRepository.findByMeeting(
                command.tenantId(), command.meetingId(), currentTranscriptVersion);
            return new GenerationInput(
                meeting.title(), currentTranscriptVersion, currentMinutesVersion, segments,
                glossaryBlockFor(command.tenantId(), command.meetingId()),
                referenceBlockFor(command.tenantId(), command.meetingId()));
        });

    // No TX — DashScope call (gateway opens its own short TXs for template lookup + audit writes).
    LlmGateway.LlmResponse response = llmGateway.complete(new LlmGateway.LlmRequest(
        command.tenantId(), command.meetingId(), taskId, CAPABILITY, TASK_NAME,
        buildLlmContext(input, command.meetingId()), null, null));

    // Short TX #2 — re-validate version, persist new minutes version, publish events.
    return tenantScopedTransaction.execute(
        command.tenantId(), command.requestedBy(), command.requestId(), () -> {
            int nowVersion = transcriptRepository.currentTranscriptVersion(command.tenantId(), command.meetingId());
            if (nowVersion != input.transcriptVersion()) {
                throw new VersionConflictException("transcript changed during minutes generation: was="
                    + input.transcriptVersion() + " now=" + nowVersion);
            }
            Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById = new HashMap<>();
            for (var seg : input.segments()) segmentById.put(seg.segmentId(), seg);
            ParsedMinutes parsed = parse(response.structuredJson() != null ? response.structuredJson() : response.content());
            List<MinutesRepository.SectionRecord> sectionRecords = enrichEvidence(parsed.sections(), segmentById);
            int newMinutesVersion = input.minutesVersion() + 1;
            OffsetDateTime now = OffsetDateTime.now(clock);
            String minutesId = "min_" + UUID.randomUUID().toString().replace("-", "");
            MinutesRepository.MinutesRecord record = new MinutesRepository.MinutesRecord(
                minutesId, command.tenantId(), command.meetingId(), newMinutesVersion, input.transcriptVersion(),
                parsed.title(), parsed.markdown(), sectionRecords, "PUBLISHED", StaleStatus.ACTIVE,
                response.artifactManifestId(), command.requestedBy(), now, now);
            minutesRepository.save(record);
            minutesRepository.incrementMeetingMinutesVersion(command.tenantId(), command.meetingId(), newMinutesVersion);
            log.info("minutes_regenerated tenant={} meeting={} minutesVersion={}",
                command.tenantId(), command.meetingId(), newMinutesVersion);
            publishMinutesGenerated(command.tenantId(), command.meetingId(), minutesId,
                newMinutesVersion, input.transcriptVersion(), now);
            return toDto(record);
        });
}

private record GenerationInput(
    String meetingTitle, int transcriptVersion, int minutesVersion,
    List<TranscriptRepository.TranscriptSegmentRecord> segments,
    String glossaryBlock, String referenceBlock
) {}
```

`buildLlmContext` changes signature to `(GenerationInput input, String meetingId)` and uses `input.meetingTitle()` / `input.segments()` / pre-loaded blocks (no repo access). Make `ParsedMinutes` a record-style accessor (`parsed.sections()` etc. — it already is a private record; keep it). Note: `glossaryBlockFor`/`referenceBlockFor` read repositories, hence they run inside TX #1.

- [ ] **Step 4: Restructure `ExtractionApplicationService`** the same way:

```java
public ExtractionSummary extractForTask(String tenantId, String meetingId, String taskId) {
    // Short TX #1 — load meeting + transcript snapshot.
    ExtractionInput input = tenantScopedTransaction.execute(tenantId, null, null, () -> {
        Meeting meeting = meetingRepository.findById(tenantId, meetingId)
            .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + meetingId));
        int transcriptVersion = transcriptRepository.currentTranscriptVersion(tenantId, meetingId);
        return new ExtractionInput(meeting.title(), transcriptVersion,
            transcriptRepository.findByMeeting(tenantId, meetingId, transcriptVersion));
    });

    // No TX — DashScope call.
    LlmGateway.LlmResponse response = llmGateway.complete(new LlmGateway.LlmRequest(
        tenantId, meetingId, taskId, CAPABILITY, TASK_NAME,
        Map.of("meetingTitle", input.meetingTitle(), "meetingId", meetingId,
            "transcript", renderTranscript(input.segments())),
        null, null));

    // Short TX #2 — parse + persist drafts.
    return tenantScopedTransaction.execute(tenantId, null, null, () -> {
        Map<String, TranscriptRepository.TranscriptSegmentRecord> segmentById = new HashMap<>();
        for (var seg : input.segments()) segmentById.put(seg.segmentId(), seg);
        JsonNode root = parseJson(response.structuredJson() != null ? response.structuredJson() : response.content());
        OffsetDateTime now = OffsetDateTime.now(clock);
        int actions = persistActionItems(root, tenantId, meetingId, input.transcriptVersion(), segmentById, response.artifactManifestId(), now);
        int decisions = persistDecisions(root, tenantId, meetingId, input.transcriptVersion(), segmentById, response.artifactManifestId(), now);
        int risks = persistRisks(root, tenantId, meetingId, input.transcriptVersion(), segmentById, response.artifactManifestId(), now);
        log.info("extraction_completed tenant={} meeting={} actions={} decisions={} risks={}",
            tenantId, meetingId, actions, decisions, risks);
        return new ExtractionSummary(actions, decisions, risks);
    });
}

private record ExtractionInput(String meetingTitle, int transcriptVersion,
    List<TranscriptRepository.TranscriptSegmentRecord> segments) {}
```

(`doExtract` is dissolved into this method.)

- [ ] **Step 5: Restructure `RagQueryApplicationService`.** `query(...)` drops the outer `tenantScopedTransaction.execute`; `doQuery` wraps only its DB phases:

```java
@Override
public RagAnswerDTO query(RagQueryCommand command) {
    RagAnswerCache.RagCacheKey cacheKey = toCacheKey(command);
    var cached = answerCache.lookup(cacheKey);
    if (cached.isPresent()) {
        log.info("rag_query_cache_hit tenant={} user={} citations={} coverage={}",
            command.tenantId(), command.userId(), cached.get().citations().size(), cached.get().coverage());
        return cached.get();
    }
    RagAnswerDTO answer = doQuery(command);
    if (answer.artifactManifestId() != null) {
        answerCache.store(cacheKey, answer, coverageOf(answer));
    }
    return answer;
}

private <T> T inShortTx(RagQueryCommand command, java.util.function.Supplier<T> work) {
    return tenantScopedTransaction.execute(command.tenantId(), command.userId(), command.requestId(), work);
}
```

Inside `doQuery`, wrap each DB phase with `inShortTx(command, () -> ...)` while leaving the HTTP phases bare:
- `authorizationService.authorizeScope(...)` → `inShortTx`
- `embeddingGateway.embed(...)` → **no TX** (unchanged position)
- the retrieval block (`searchByVector` + `searchByKeyword` + `RrfFusion.fuse`) → one `inShortTx` returning the fused list
- `authorizationService.filterAuthorized(...)` → `inShortTx`
- `rerankOrFallback(...)` → **no TX**
- `enrich(tenantId, top)` (citation title/segment lookups) → `inShortTx`
- `llmGateway.complete(...)` → **no TX**

(Cache placement moves *after* authorization in Task 13/D10 — do not do it here; this task only changes transaction boundaries.)

- [ ] **Step 6: `DashScopeLlmGateway` self-managed short TXs.** Add constructor parameter `TenantScopedTransaction tenantTx` (import `com.meeting.api.app.common.TenantScopedTransaction`; update both constructors and the Spring wiring — infrastructure→app is allowed). Restructure `complete`:

```java
@Override
public LlmResponse complete(LlmRequest request) {
    // Short TX #1 — template lookup (RLS: prompt_templates is tenant_or_global).
    PromptTemplateRepository.PromptTemplate template = tenantTx.execute(request.tenantId(), null, null, () ->
        promptTemplateRepository.findActiveByTaskName(request.tenantId(), request.taskName())
            .or(() -> promptTemplateRepository.findActiveByTaskName(null, request.taskName()))
            .orElseThrow(() -> new LlmProviderException(ErrorCode.LLM_SCHEMA_INVALID,
                "active prompt template not found for task " + request.taskName())));
    String rendered = renderTemplate(template.templateBody(), request.variables());
    String inputHash = sha256(rendered);
    OffsetDateTime startedAt = OffsetDateTime.now(clock);

    OpenAiCompatibleChatClient.ChatCompletion completion;
    try {
        completion = client.chatComplete(/* unchanged — NO transaction around the HTTP call */);
    } catch (LlmProviderException ex) {
        tenantTx.executeWithoutResult(request.tenantId(), null, null, () ->
            recordFailedCall(request, template, inputHash, ex.errorCode().name(), 0, startedAt));
        throw ex;
    }
    // ... schema validation unchanged ...
    // Short TX #2 — manifest + call-log audit writes.
    OffsetDateTime now = OffsetDateTime.now(clock);
    record Persisted(String manifestId, String callLogId) {}
    Persisted persisted = tenantTx.execute(request.tenantId(), null, null, () -> new Persisted(
        recordArtifactManifest(request, template, inputHash, outputHash, completion, now),
        recordSuccessfulCall(request, template, inputHash, outputHash, completion, startedAt)));
    return new LlmResponse(completion.content(), structuredJson, completion.promptTokens(),
        completion.completionTokens(), completion.latencyMs(), completion.modelVersion(),
        persisted.callLogId(), persisted.manifestId());
}
```

(When invoked from a caller that is *already* in a TX — none after this task — `TenantTransactionTemplate` uses default `REQUIRED` propagation and would join; that is acceptable.) Update `DashScopeLlmGatewayTest` constructions with `TenantScopedTransaction.immediate()`.

- [ ] **Step 7: Run** `-Dtest=LlmCallsOutsideTransactionTest` → pass, then `-Dtest='MinutesApplicationServiceTest,ExtractionApplicationServiceTest,RagQueryApplicationServiceTest,DashScopeLlmGatewayTest,JavaLlmPhaseOrchestratorTest'` → all pass (these use `TenantScopedTransaction.immediate()`, so behavior-level assertions are unaffected; fix any constructor-arity fallout only).

- [ ] **Step 8: Commit** — `git add -A && git commit -m "fix(api): three-phase TX pattern for minutes/extraction/RAG/LLM-gateway, no TX around external calls (C2,D1)"`

## Task 5: C5 + C4-part1 (D2) — callback-claimed leases, 120s TTL, lease cleared at phase boundaries

Verified: all three creation paths in `ProcessingTaskApplicationService` (lines 165-170, 229-234, 285-290) and `EmbeddingTaskDispatcher` (lines 134-139) pre-claim `claimLease("worker_dev_001", ..., now.plusMinutes(5))` — tasks are RUNNING before any worker consumed the message. `completeWorkerPhase`/`beginJavaLlm` never clear the lease, so held/LLM-phase tasks get ORPHANED by the scanner. Heartbeats extend by +5min (`ProcessingTaskCallbackApplicationService.java:132`). New model: tasks are created QUEUED with null lease; the FIRST authenticated callback of an attempt claims the lease (`{workerId}:{taskId}:{attemptNo}` format, no worker change); every callback renews to now+120s; `completeWorkerPhase` clears it; `findExpiredLeases` only looks at `phase='WORKER_DAG_RUNNING'`.

**Files:**
- Modify: `domain/task/ProcessingTask.java`, `app/task/ProcessingTaskCallbackApplicationService.java`, `app/task/ProcessingTaskApplicationService.java`, `app/rag/EmbeddingTaskDispatcher.java`, `app/speaker/SpeakerEnrollmentCallbackApplicationService.java`, `app/speaker/SpeakerCandidatesCallbackApplicationService.java`, `infrastructure/persistence/task/JdbcProcessingTaskRepository.java`, `meeting-api-start/src/main/resources/application.yml`
- Create test: `LeaseClaimOnCallbackTest.java`; Modify: `ProcessingTaskApplicationServiceTest.java`, `JdbcProcessingTaskRepositoryIT.java`

- [ ] **Step 1: Write failing domain test** (new methods in `LeaseClaimOnCallbackTest.java`):

```java
package com.meeting.api;

import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.TaskLeaseConflictException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeaseClaimOnCallbackTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-12T10:00:00Z");

    private static ProcessingTask queuedTask() {
        ProcessingTask task = ProcessingTask.create("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE",
            List.of(ProcessingStep.AUDIO_UPLOAD, ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE,
                ProcessingStep.SUMMARY, ProcessingStep.EXTRACTION), NOW);
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        return task;
    }

    @Test
    void firstCallbackClaimsLeaseAndMovesQueuedToRunning() {
        ProcessingTask task = queuedTask();
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.QUEUED);
        assertThat(task.leaseOwner()).isNull();

        task.claimOrRenewLease(1, "worker_a:task_01:1", NOW.plusMinutes(1));

        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.RUNNING);
        assertThat(task.leaseOwner()).isEqualTo("worker_a:task_01:1");
        assertThat(task.leaseExpiresAt()).isEqualTo(NOW.plusMinutes(1).plus(ProcessingTask.LEASE_TTL));
    }

    @Test
    void differentOwnerOnLiveLeaseConflicts_expiredLeaseIsReclaimable() {
        ProcessingTask task = queuedTask();
        task.claimOrRenewLease(1, "worker_a:task_01:1", NOW);
        assertThatThrownBy(() -> task.claimOrRenewLease(1, "worker_b:task_01:1", NOW.plusSeconds(30)))
            .isInstanceOf(TaskLeaseConflictException.class);
        // After expiry (TTL 120s) a different owner may take over the same attempt.
        task.claimOrRenewLease(1, "worker_b:task_01:1", NOW.plusSeconds(121));
        assertThat(task.leaseOwner()).isEqualTo("worker_b:task_01:1");
    }

    @Test
    void completeWorkerPhaseClearsLease() {
        ProcessingTask task = queuedTask();
        task.claimOrRenewLease(1, "worker_a:task_01:1", NOW);
        task.completeWorkerPhase(ProcessingTaskStatus.SUCCEEDED,
            List.of(ProcessingStep.ASR, ProcessingStep.TRANSCRIPT_MERGE), List.of(),
            1, "worker_a:task_01:1", NOW.plusMinutes(1));
        assertThat(task.leaseOwner()).isNull();
        assertThat(task.leaseExpiresAt()).isNull();
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (`claimOrRenewLease` / `LEASE_TTL` missing):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw -pl meeting-api-start -am test -Dtest=LeaseClaimOnCallbackTest
```

- [ ] **Step 3: Implement domain changes in `ProcessingTask`:**

```java
/** SPEC §6 / D2: worker lease TTL. Heartbeats every 15–30s; expiry → ORPHANED. */
public static final java.time.Duration LEASE_TTL = java.time.Duration.ofSeconds(120);

/**
 * D2 lease model: tasks are created QUEUED with no lease; the first callback of an
 * attempt claims it, later callbacks renew it. Conflicting live lease → 409.
 */
public void claimOrRenewLease(int callbackAttemptNo, String callbackLeaseOwner, OffsetDateTime now) {
    if (callbackAttemptNo != attemptNo) {
        throw new TaskAttemptConflictException(
            "callback attempt " + callbackAttemptNo + " does not match current attempt " + attemptNo);
    }
    requireNonTerminal();
    if (status != ProcessingTaskStatus.QUEUED && status != ProcessingTaskStatus.RUNNING) {
        throw new TaskLeaseConflictException("task status " + status + " does not accept worker lease claims");
    }
    boolean expired = leaseExpiresAt == null || !leaseExpiresAt.isAfter(now);
    if (leaseOwner == null || expired) {
        leaseOwner = requireText(callbackLeaseOwner, "leaseOwner");
    } else if (!leaseOwner.equals(callbackLeaseOwner)) {
        throw new TaskLeaseConflictException(
            "callback lease owner " + callbackLeaseOwner + " does not match current lease " + leaseOwner);
    }
    leaseExpiresAt = now.plus(LEASE_TTL);
    heartbeatAt = now;
    if (status == ProcessingTaskStatus.QUEUED) {
        status = ProcessingTaskStatus.RUNNING;
    }
    touch(now);
}
```

In `completeWorkerPhase`, after `phase = ProcessingTaskPhase.WORKER_DAG_DONE;` add:

```java
        leaseOwner = null;
        leaseExpiresAt = null;
```

…and **relax `validateCallback`'s lease equality only for the post-claim path**: keep `validateCallback` as-is (it now throws the typed exceptions from Task 2); after `claimOrRenewLease` has run, the owners always match, and `completeWorkerPhase`/`updateWorkerStep`/`heartbeat` keep their internal validation as a second guard. Keep the old `claimLease(...)` method — it remains a valid transition used by tests; production creation paths stop calling it.

- [ ] **Step 4: Run Step 1 test — expect pass.**

- [ ] **Step 5: Remove creation-time pre-claims.** Delete the `task.claimLease("worker_dev_001", ...)` blocks from `ProcessingTaskApplicationService.create`, `createForCompletedAudioUpload`, `createForSpeakerEnrollment`, and `EmbeddingTaskDispatcher.dispatch` — tasks are saved right after `enqueue(now)`. Update `ProcessingTaskApplicationServiceTest` / `EmbeddingTaskDispatcherTest` expectations: created task `status=QUEUED`, `leaseOwner=null`, `leaseExpiresAt=null`.

- [ ] **Step 6: Claim on every callback.** In `ProcessingTaskCallbackApplicationService` add a private helper and call it as the first mutation inside each handler TX (after `load(...)` + meeting-linkage check, before idempotency record):

```java
private static void claimLeaseForCallback(ProcessingTask task, int attemptNo, CallbackMetadata metadata, OffsetDateTime now) {
    task.claimOrRenewLease(attemptNo, metadata.leaseOwner(), now);
}
```

Call sites: `updateStep` (before `persistCallbackEvent`), `completeWorkerPhase`, `fail`, `writeTranscript`. In `heartbeat`, replace `command.heartbeatAt().plusMinutes(5)` with:

```java
task.claimOrRenewLease(command.attemptNo(), command.metadata().leaseOwner(), command.heartbeatAt());
task.heartbeat(command.stepName(), command.progress(), command.attemptNo(),
    command.metadata().leaseOwner(), command.heartbeatAt(), command.heartbeatAt().plus(ProcessingTask.LEASE_TTL));
```

In `SpeakerEnrollmentCallbackApplicationService` (lines ~88-93) and `SpeakerCandidatesCallbackApplicationService` (lines ~100-106), replace the attempt + strict lease-equality checks with `task.claimOrRenewLease(command.attemptNo(), command.metadata().leaseOwner(), OffsetDateTime.now(clock));` **followed by `taskRepository.save(task)` at the end of the TX** so the claimed lease persists (these two services currently never save the task — add the save). Without this, the first speaker callback after this task would be rejected (lease is null at that point) — verified breakage, must ship in the same commit.

- [ ] **Step 7: Scanner scope fix.** `JdbcProcessingTaskRepository.findExpiredLeases`: change `AND phase <> 'TERMINAL'` to `AND phase = 'WORKER_DAG_RUNNING'`. Add an IT case to `JdbcProcessingTaskRepositoryIT`:

```java
@Test
void findExpiredLeasesIgnoresWorkerDagDoneAndJavaLlmPhases() {
    ProcessingTask running = ProcessingTask.create("task_lease_run", TENANT, MEETING, "MEETING_FULL_PIPELINE", FULL_TASK_STEPS, NOW);
    running.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
    running.enqueue(NOW);
    running.claimOrRenewLease(1, "worker_a:task_lease_run:1", NOW);
    tx.executeWithoutResult(s -> repo.save(running));

    ProcessingTask done = ProcessingTask.create("task_lease_done", TENANT, MEETING, "MEETING_FULL_PIPELINE", FULL_TASK_STEPS, NOW);
    done.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
    done.enqueue(NOW);
    done.claimOrRenewLease(1, "worker_a:task_lease_done:1", NOW);
    done.completeWorkerPhase(ProcessingTaskStatus.PARTIAL_SUCCEEDED,
        List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.DIARIZATION,
            ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING, ProcessingStep.TRANSCRIPT_MERGE),
        List.of(new WorkerPhaseCompletedEvent.SkippedStep(ProcessingStep.ALIGNMENT, "skip"),
            new WorkerPhaseCompletedEvent.SkippedStep(ProcessingStep.RAG_INDEXING, "skip")),
        1, "worker_a:task_lease_done:1", NOW);
    tx.executeWithoutResult(s -> repo.save(done));

    var expired = tx.execute(s -> repo.findExpiredLeases(TENANT, NOW.plusHours(1), 10));
    assertThat(expired).extracting(ProcessingTaskRepository.ExpiredLease::taskId)
        .containsExactly("task_lease_run");   // WORKER_DAG_DONE row has no lease and wrong phase
}
```

- [ ] **Step 8: Config doc value.** `application.yml`: `meeting.task.lease-duration-seconds: 300` → `120` (no Java reader — documentation key; the enforced value is `ProcessingTask.LEASE_TTL`).

- [ ] **Step 9: Run** `./mvnw -pl meeting-api-start -am test` (unit) — fix fallout in `ProcessingTaskCallbackApplicationServiceTest` (tasks there must now be arranged via `enqueue` + first-callback claim instead of `claimLease`), then `./mvnw verify -q` for the IT (Docker required).

- [ ] **Step 10: Commit** — `git add -A && git commit -m "fix(api): worker leases claimed by first callback, 120s TTL, cleared at worker-phase end (C5,C4,D2)"`

## Task 6: C4-part2 (D2) + I10 — orphan requeue with republish, retry republish, CANCEL_PENDING closure, row locks

Verified: `ProcessingTask.requeueOrphaned` and `confirmCancelled` have **zero production callers** (only `ProcessingTaskDomainTest`); `requeueOrphaned` forgets to reset `phase`; `confirmCancelled` marks SKIPPED steps CANCELLED; `ProcessingTaskApplicationService.retry` flips to QUEUED but publishes no MQ message; `JdbcProcessingTaskRepository.save` is a blind upsert with no row lock (I10). Republish needs the original task-message payload (audioUri/options are not on the aggregate) — source it from the committed outbox row via a new `TaskMessageArchive` port.

**Files:**
- Create: `domain/task/TaskMessageArchive.java`, `infrastructure/mq/OutboxTaskMessageArchive.java`, `app/task/ProcessingTaskRequeueService.java`
- Modify: `domain/task/ProcessingTask.java`, `domain/task/ProcessingTaskRepository.java`, `infrastructure/persistence/task/JdbcProcessingTaskRepository.java`, `app/task/ProcessingTaskLeaseScanner.java`, `app/task/ProcessingTaskApplicationService.java`, `app/task/ProcessingTaskCallbackApplicationService.java`, `app/task/TaskStepProgressService.java`, `start/config/ProcessingTaskLeaseScannerConfig.java`
- Create test: `ProcessingTaskRequeueServiceTest.java`; Modify: `ProcessingTaskDomainTest.java`, `ProcessingTaskLeaseScannerTest.java`, `ProcessingTaskApplicationServiceTest.java`

- [ ] **Step 1: Write failing test** (`ProcessingTaskRequeueServiceTest.java`; reuse `LlmPhaseRecoveryScannerTest.InMemoryRepo`):

```java
package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.ProcessingTaskRequeueService;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.common.DomainEvent;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.TaskMessageArchive;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingTaskRequeueServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);

    static final class CapturingPublisher implements com.meeting.api.domain.task.MessagePublisher {
        final List<DomainEvent> published = new ArrayList<>();
        @Override public void publish(DomainEvent event) { published.add(event); }
    }

    private static Map<String, Object> archivedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", "task_01");
        payload.put("taskType", "MEETING_FULL_PIPELINE");
        payload.put("tenantId", "tenant_01");
        payload.put("meetingId", "meeting_01");
        payload.put("attemptNo", 1);
        payload.put("pipelineSteps", List.of("AUDIO_PREPROCESS", "ASR", "TRANSCRIPT_MERGE"));
        payload.put("audioUri", "tos://meeting-audio/a.wav");
        return payload;
    }

    private static ProcessingTask orphanedTask(int attemptNo) {
        ProcessingTask task = ProcessingTask.create("task_01", "tenant_01", "meeting_01", "MEETING_FULL_PIPELINE",
            List.of(ProcessingStep.AUDIO_UPLOAD, ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR,
                ProcessingStep.TRANSCRIPT_MERGE, ProcessingStep.SUMMARY, ProcessingStep.EXTRACTION), NOW);
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        for (int attempt = 1; attempt <= attemptNo; attempt++) {
            task.claimOrRenewLease(attempt, "worker_a:task_01:" + attempt, NOW);
            assertThat(task.markOrphanedIfLeaseExpired(NOW.plusSeconds(121))).isTrue();
            if (attempt < attemptNo) {
                task.requeueOrphaned(NOW.plusSeconds(130));
            }
        }
        return task;
    }

    @Test
    void requeueResetsPhaseBumpsAttemptAndRepublishesArchivedMessage() {
        var repo = new LlmPhaseRecoveryScannerTest.InMemoryRepo();
        repo.save(orphanedTask(1));
        CapturingPublisher publisher = new CapturingPublisher();
        TaskMessageArchive archive = (tenantId, taskId) -> Optional.of(archivedPayload());
        ProcessingTaskRequeueService service = new ProcessingTaskRequeueService(
            repo, archive, publisher, TenantScopedTransaction.immediate(), CLOCK, 3);

        var outcome = service.requeueOrphaned("tenant_01", "task_01");

        assertThat(outcome).isEqualTo(ProcessingTaskRequeueService.Outcome.REQUEUED);
        ProcessingTask task = repo.store.get("task_01");
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.QUEUED);
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.WORKER_DAG_RUNNING);   // requeueOrphaned must reset phase
        assertThat(task.attemptNo()).isEqualTo(2);
        ProcessingTaskCreatedEvent event = (ProcessingTaskCreatedEvent) publisher.published.get(0);
        assertThat(event.attemptNo()).isEqualTo(2);
        assertThat(event.payload().get("attemptNo")).isEqualTo(2);
        assertThat(event.payload().get("audioUri")).isEqualTo("tos://meeting-audio/a.wav");
    }

    @Test
    void exhaustedAttemptsLandFailedWithTaskRetryExhausted() {
        var repo = new LlmPhaseRecoveryScannerTest.InMemoryRepo();
        repo.save(orphanedTask(3));   // attemptNo == maxAttempts
        ProcessingTaskRequeueService service = new ProcessingTaskRequeueService(
            repo, (t, id) -> Optional.of(archivedPayload()), new CapturingPublisher(),
            TenantScopedTransaction.immediate(), CLOCK, 3);

        var outcome = service.requeueOrphaned("tenant_01", "task_01");

        assertThat(outcome).isEqualTo(ProcessingTaskRequeueService.Outcome.RETRY_EXHAUSTED);
        ProcessingTask task = repo.store.get("task_01");
        assertThat(task.status()).isEqualTo(ProcessingTaskStatus.FAILED);
        assertThat(task.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(task.lastErrorCode()).isEqualTo("TASK_RETRY_EXHAUSTED");
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (no `TaskMessageArchive`, no `ProcessingTaskRequeueService`).

- [ ] **Step 3: Implement.** Domain port:

```java
package com.meeting.api.domain.task;

import java.util.Map;
import java.util.Optional;

/** Read-back of the last published task message payload (sourced from the outbox audit trail). */
public interface TaskMessageArchive {
    Optional<Map<String, Object>> latestTaskMessagePayload(String tenantId, String taskId);
}
```

Infrastructure impl:

```java
package com.meeting.api.infrastructure.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.domain.task.TaskMessageArchive;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxTaskMessageArchive implements TaskMessageArchive {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxTaskMessageArchive(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Map<String, Object>> latestTaskMessagePayload(String tenantId, String taskId) {
        List<String> rows = jdbcTemplate.query(
            """
            SELECT payload_json::text
              FROM domain_events_outbox
             WHERE tenant_id = ? AND aggregate_type = 'ProcessingTask'
               AND aggregate_id = ? AND event_type = 'ProcessingTaskCreatedEvent'
             ORDER BY sequence_no DESC
             LIMIT 1
            """,
            (rs, rowNum) -> rs.getString(1), tenantId, taskId);
        if (rows.isEmpty()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(rows.get(0), MAP_TYPE));
        } catch (Exception e) {
            throw new IllegalStateException("archived task message is not valid JSON for " + taskId, e);
        }
    }
}
```

Domain fixes in `ProcessingTask`:

```java
public void requeueOrphaned(OffsetDateTime now) {
    requireStatus(ProcessingTaskStatus.ORPHANED);
    requireNonTerminal();
    attemptNo += 1;
    status = ProcessingTaskStatus.QUEUED;
    phase = ProcessingTaskPhase.WORKER_DAG_RUNNING;   // was missing — review C4
    currentStep = null;
    lastErrorCode = null;
    retryable = false;
    heartbeatAt = null;
    for (ProcessingTaskStep step : steps.values()) {
        if (step.source() == ProcessingStepUpdateSource.AI_WORKER_CALLBACK) {
            step.resetForAttempt();
        }
    }
    touch(now);
}

public void confirmCancelled(OffsetDateTime now) {
    requireStatus(ProcessingTaskStatus.CANCEL_PENDING);
    completeTerminal(ProcessingTaskStatus.CANCELLED, null, now);
    for (ProcessingTaskStep step : steps.values()) {
        StepStatus s = step.status();
        if (s == StepStatus.PENDING || s == StepStatus.QUEUED || s == StepStatus.RUNNING) {
            step.markCancelled(now);   // SUCCEEDED/FAILED/SKIPPED keep their state — review minor
        }
    }
}
```

App service (`app/task/ProcessingTaskRequeueService.java`):

```java
package com.meeting.api.app.task;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.task.MessagePublisher;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskCreatedEvent;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.domain.task.TaskMessageArchive;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProcessingTaskRequeueService {
    private static final Logger log = LoggerFactory.getLogger(ProcessingTaskRequeueService.class);

    public enum Outcome { REQUEUED, RETRY_EXHAUSTED, SKIPPED }

    private final ProcessingTaskRepository taskRepository;
    private final TaskMessageArchive taskMessageArchive;
    private final MessagePublisher messagePublisher;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;
    private final int maxAttempts;

    @Autowired
    public ProcessingTaskRequeueService(
        ProcessingTaskRepository taskRepository,
        TaskMessageArchive taskMessageArchive,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        @Value("${meeting.task.max-attempts:3}") int maxAttempts
    ) {
        this(taskRepository, taskMessageArchive, messagePublisher, tenantScopedTransaction, Clock.systemUTC(), maxAttempts);
    }

    public ProcessingTaskRequeueService(
        ProcessingTaskRepository taskRepository,
        TaskMessageArchive taskMessageArchive,
        MessagePublisher messagePublisher,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock,
        int maxAttempts
    ) {
        this.taskRepository = taskRepository;
        this.taskMessageArchive = taskMessageArchive;
        this.messagePublisher = messagePublisher;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    public Outcome requeueOrphaned(String tenantId, String taskId) {
        return tenantScopedTransaction.execute(tenantId, "lease-scanner", "requeue-" + taskId, () -> {
            ProcessingTask task = taskRepository.findByIdForUpdate(tenantId, taskId).orElse(null);
            if (task == null || task.status() != ProcessingTaskStatus.ORPHANED) {
                return Outcome.SKIPPED;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (task.attemptNo() >= maxAttempts) {
                task.completeTerminal(ProcessingTaskStatus.FAILED, ErrorCode.TASK_RETRY_EXHAUSTED.name(), now);
                taskRepository.save(task);
                log.warn("task_retry_exhausted task={} tenant={} attempts={}", taskId, tenantId, task.attemptNo());
                return Outcome.RETRY_EXHAUSTED;
            }
            Map<String, Object> payload = taskMessageArchive.latestTaskMessagePayload(tenantId, taskId).orElse(null);
            if (payload == null) {
                task.completeTerminal(ProcessingTaskStatus.FAILED, ErrorCode.INVALID_TASK_MESSAGE.name(), now);
                taskRepository.save(task);
                log.error("task_requeue_no_archived_message task={} tenant={}", taskId, tenantId);
                return Outcome.RETRY_EXHAUSTED;
            }
            task.requeueOrphaned(now);
            taskRepository.save(task);
            publishTaskMessage(task, payload, now);
            log.info("task_requeued task={} tenant={} attempt={}", taskId, tenantId, task.attemptNo());
            return Outcome.REQUEUED;
        });
    }

    /** Shared by requeue and the public retry use case — republishes the archived message with the new attemptNo. */
    public void publishTaskMessage(ProcessingTask task, Map<String, Object> archivedPayload, OffsetDateTime now) {
        Map<String, Object> payload = new LinkedHashMap<>(archivedPayload);
        payload.put("attemptNo", task.attemptNo());
        messagePublisher.publish(new ProcessingTaskCreatedEvent(
            "evt_" + UUID.randomUUID().toString().replace("-", ""),
            task.tenantId(), task.taskId(), task.meetingId(), task.taskType(), task.attemptNo(),
            pipelineStepsOf(payload), 0, now, payload));
    }

    private static List<ProcessingStep> pipelineStepsOf(Map<String, Object> payload) {
        Object raw = payload.get("pipelineSteps");
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalStateException("archived task message missing pipelineSteps");
        }
        return values.stream().map(v -> ProcessingStep.valueOf(String.valueOf(v))).toList();
    }
}
```

- [ ] **Step 4: Run Step 1 tests — expect pass.**

- [ ] **Step 5: Wire the scanner.** `ProcessingTaskRepository` gains `default List<ExpiredLease> findCancelPendingWithExpiredLease(String tenantId, OffsetDateTime now, int limit) { return List.of(); }`; JDBC impl:

```java
@Override
public List<ExpiredLease> findCancelPendingWithExpiredLease(String tenantId, OffsetDateTime now, int limit) {
    return jdbcTemplate.query(
        """
        SELECT tenant_id, id
          FROM processing_tasks
         WHERE tenant_id = ? AND status = 'CANCEL_PENDING'
           AND (lease_expires_at IS NULL OR lease_expires_at < ?)
         ORDER BY updated_at ASC
         LIMIT ?
        """,
        (rs, rowNum) -> new ExpiredLease(rs.getString("tenant_id"), rs.getString("id")),
        tenantId, Timestamp.from(now.toInstant()), limit);
}
```

`ProcessingTaskLeaseScanner`: add optional `ProcessingTaskRequeueService requeueService` constructor parameter (existing 4-arg ctor delegates with `null`); in `scanOnce`, after a successful `transitionLease(...)` call `requeueService.requeueOrphaned(lease.tenantId(), lease.taskId())` when non-null (own TX inside); then add a cancel-confirmation pass per tenant:

```java
for (ProcessingTaskRepository.ExpiredLease pending : tenantScopedTransaction.execute(
        tenantId, "lease-scanner", "cancel-scan-" + tenantId,
        () -> taskRepository.findCancelPendingWithExpiredLease(tenantId, now, batchSize))) {
    cancelled += tenantScopedTransaction.execute(pending.tenantId(), "lease-scanner", "cancel-" + pending.taskId(), () -> {
        ProcessingTask task = taskRepository.findByIdForUpdate(pending.tenantId(), pending.taskId()).orElse(null);
        if (task == null || task.status() != ProcessingTaskStatus.CANCEL_PENDING) return 0;
        task.confirmCancelled(OffsetDateTime.now(clock));
        taskRepository.save(task);
        return 1;
    });
}
```

`ScanReport` becomes `record ScanReport(int scanned, int orphaned, int requeued, int cancelled)` — update `ProcessingTaskLeaseScannerTest` constructions/assertions and `ProcessingTaskLeaseScannerConfig` (inject `ProcessingTaskRequeueService`, pass through).

- [ ] **Step 6: Retry republish + CANCEL_PENDING gate + row locks.** In `ProcessingTaskApplicationService`: add `TaskMessageArchive taskMessageArchive` + `ProcessingTaskRequeueService requeueService` as nullable fields on the widest constructor (the `@Autowired` constructor injects them; legacy convenience ctors pass null); `retry(...)` becomes:

```java
@Override
public ProcessingTaskDTO retry(RetryTaskCommand command) {
    return tenantScopedTransaction.execute(command.tenantId(), command.requestedBy(), command.requestId(), () -> {
        ProcessingTask task = taskRepository.findByIdForUpdate(command.tenantId(), command.taskId())
            .orElseThrow(() -> new ApplicationException(ErrorCode.TASK_NOT_FOUND, 404, "task not found: " + command.taskId(), false));
        OffsetDateTime now = OffsetDateTime.now(clock);
        task.retry(now);
        ProcessingTask saved = taskRepository.save(task);
        if (taskMessageArchive != null && requeueService != null) {
            Map<String, Object> payload = taskMessageArchive.latestTaskMessagePayload(command.tenantId(), command.taskId())
                .orElseThrow(() -> new ApplicationException(ErrorCode.INVALID_TASK_MESSAGE, 422,
                    "no archived task message to republish for " + command.taskId(), false));
            requeueService.publishTaskMessage(saved, payload, now);
        } else {
            log.warn("task_retry_without_republish task={} (archive not wired)", command.taskId());
        }
        return ProcessingTaskAssembler.toDto(saved);
    });
}
```

(Add an slf4j `Logger log` to the class.) In `ProcessingTaskCallbackApplicationService.claimLeaseForCallback` prepend the CANCEL_PENDING gate (worker aborts on it — P1 coordination):

```java
if (task.status() == ProcessingTaskStatus.CANCEL_PENDING) {
    throw new ApplicationException(ErrorCode.TASK_CANCELLED, 409,
        "task cancellation requested: " + task.taskId(), false);
}
```

I10 row locks: switch `load(...)` in `ProcessingTaskCallbackApplicationService`, `TaskStepProgressService`, `EmbeddingsCallbackApplicationService`, `SpeakerEnrollmentCallbackApplicationService`, `SpeakerCandidatesCallbackApplicationService` from `findById` to `findByIdForUpdate` (all already run inside a tenant TX, so the lock holds until commit and serializes heartbeat-vs-complete races).

- [ ] **Step 7: Tests + run.** Update `ProcessingTaskDomainTest` (requeue resets phase; cancelled SKIPPED step survives), add a retry-republish case to `ProcessingTaskApplicationServiceTest` (capturing publisher sees `ProcessingTaskCreatedEvent` with bumped attempt), extend `ProcessingTaskLeaseScannerTest` for the cancel pass. Run `./mvnw -pl meeting-api-start -am test`, then full `./mvnw test`.

- [ ] **Step 8: Commit** — `git add -A && git commit -m "fix(api): orphan requeue+republish, retry republish, CANCEL_PENDING closure, row-locked task writes (C4,D2,I10)"`

## Task 7: I6 (D4) — callback nonce store + monotonic heartbeat that still renews the lease

`CallbackSecurityVerifier` checks only signature + ±300s skew — a captured heartbeat is replayable for 5 minutes to extend a dead worker's lease or rewind progress. Add a `callback_nonces` dedup table checked inside every callback TX (including heartbeats), plus a monotonic-progress guard. **P1 coordination:** heartbeats arrive every 20s as `RUNNING(progress=1)` with a *stable* per-attempt idempotency key but a *fresh nonce per request*; the guard must only suppress the progress write — the lease renewal must still happen, otherwise every long step orphans.

**Files:**
- Create: `V202606121000__callback_nonces.sql`, `domain/task/CallbackNonceStore.java`, `infrastructure/persistence/task/JdbcCallbackNonceStore.java`, `app/task/CallbackReplayGuard.java`, `start/config/CallbackNonceCleanupConfig.java`
- Modify: `app/task/ProcessingTaskCallbackApplicationService.java` (+ the embeddings/speaker callback services), test `CallbackReplayGuardTest.java`

- [ ] **Step 1: Migration** `V202606121000__callback_nonces.sql` (RLS pattern matches the initial schema's `tenant_isolation` policies):

```sql
CREATE TABLE IF NOT EXISTS callback_nonces (
  tenant_id text NOT NULL REFERENCES tenants(id),
  nonce text NOT NULL,
  seen_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, nonce)
);
CREATE INDEX IF NOT EXISTS callback_nonces_seen_at_idx ON callback_nonces (seen_at);

ALTER TABLE callback_nonces ENABLE ROW LEVEL SECURITY;
ALTER TABLE callback_nonces FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON callback_nonces;
CREATE POLICY tenant_isolation ON callback_nonces
  USING (tenant_id = public.current_tenant_id())
  WITH CHECK (tenant_id = public.current_tenant_id());
```

Validate locally before pushing (ddl-check gate): `psql -v ON_ERROR_STOP=1 -f V202606121000__callback_nonces.sql` against a scratch pgvector:pg15.

- [ ] **Step 2: Port + JDBC impl.**

```java
package com.meeting.api.domain.task;

import java.time.OffsetDateTime;

public interface CallbackNonceStore {
    /** @return true when the nonce was fresh (recorded), false when already seen. */
    boolean recordIfAbsent(String tenantId, String nonce, OffsetDateTime seenAt);

    int purgeOlderThan(String tenantId, OffsetDateTime cutoff);
}
```

```java
package com.meeting.api.infrastructure.persistence.task;

import com.meeting.api.domain.task.CallbackNonceStore;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCallbackNonceStore implements CallbackNonceStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcCallbackNonceStore(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Override
    public boolean recordIfAbsent(String tenantId, String nonce, OffsetDateTime seenAt) {
        return jdbcTemplate.update(
            "INSERT INTO callback_nonces (tenant_id, nonce, seen_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            tenantId, nonce, Timestamp.from(seenAt.toInstant())) == 1;
    }

    @Override
    public int purgeOlderThan(String tenantId, OffsetDateTime cutoff) {
        return jdbcTemplate.update(
            "DELETE FROM callback_nonces WHERE tenant_id = ? AND seen_at < ?",
            tenantId, Timestamp.from(cutoff.toInstant()));
    }
}
```

- [ ] **Step 3: Guard + wiring.**

```java
package com.meeting.api.app.task;

import com.meeting.api.app.common.CallbackAuthException;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.domain.task.CallbackNonceStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SPEC §6 validation order step 4 (after HMAC + timestamp skew, before idempotency).
 * MUST run inside the tenant-scoped callback transaction: the insert is RLS-guarded
 * and rolls back with the business write, so a worker retry after a 5xx may reuse
 * its nonce while a verbatim replay of a committed request is rejected.
 */
@Component
public class CallbackReplayGuard {
    private final CallbackNonceStore nonceStore;
    private final Clock clock;

    @Autowired
    public CallbackReplayGuard(CallbackNonceStore nonceStore) { this(nonceStore, Clock.systemUTC()); }

    public CallbackReplayGuard(CallbackNonceStore nonceStore, Clock clock) {
        this.nonceStore = nonceStore;
        this.clock = clock;
    }

    public void requireFreshNonce(String tenantId, CallbackMetadata metadata) {
        if (!nonceStore.recordIfAbsent(tenantId, metadata.nonce(), OffsetDateTime.now(clock))) {
            throw new CallbackAuthException("callback nonce replayed: " + metadata.nonce());
        }
    }
}
```

Inject `CallbackReplayGuard` into `ProcessingTaskCallbackApplicationService`, `EmbeddingsCallbackApplicationService`, `SpeakerEnrollmentCallbackApplicationService`, `SpeakerCandidatesCallbackApplicationService` (nullable on legacy test ctors; skip when null) and call `replayGuard.requireFreshNonce(command.tenantId(), command.metadata())` as the first statement inside each handler TX — **including `heartbeat`**. Cleanup scheduler (`CallbackNonceCleanupConfig`, start module, mirrors the lease-scanner config): `@Scheduled(fixedDelayString = "${meeting.callback-nonce.cleanup-interval-ms:300000}")`, per active tenant `tenantScopedTransaction.executeWithoutResult(tenantId, "nonce-cleanup", null, () -> nonceStore.purgeOlderThan(tenantId, OffsetDateTime.now().minusMinutes(10)))` (10min > 2× the 5min skew window).

- [ ] **Step 4: Monotonic heartbeat (lease still renewed).** In `ProcessingTaskCallbackApplicationService.heartbeat`, after the claim from Task 5:

```java
task.claimOrRenewLease(command.attemptNo(), command.metadata().leaseOwner(), command.heartbeatAt());
ProcessingTaskStep step = task.step(command.stepName());
if (command.progress() < step.progress()) {
    // Stale/replayed progress: keep the lease extension, drop the regression (D4 + P1 contract).
    log.info("heartbeat_progress_regression_ignored task={} step={} incoming={} current={}",
        command.taskId(), command.stepName(), command.progress(), step.progress());
    return ProcessingTaskAssembler.toDto(taskRepository.save(task));
}
task.heartbeat(command.stepName(), command.progress(), command.attemptNo(),
    command.metadata().leaseOwner(), command.heartbeatAt(), command.heartbeatAt().plus(ProcessingTask.LEASE_TTL));
return ProcessingTaskAssembler.toDto(taskRepository.save(task));
```

(Add `private static final Logger log = LoggerFactory.getLogger(ProcessingTaskCallbackApplicationService.class);`.)

- [ ] **Step 5: Test** (`CallbackReplayGuardTest.java`):

```java
package com.meeting.api;

import com.meeting.api.app.common.CallbackAuthException;
import com.meeting.api.app.task.CallbackReplayGuard;
import com.meeting.api.client.internal.callback.CallbackMetadata;
import com.meeting.api.domain.task.CallbackNonceStore;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackReplayGuardTest {
    static final class InMemoryNonceStore implements CallbackNonceStore {
        final Set<String> seen = new HashSet<>();
        @Override public boolean recordIfAbsent(String tenantId, String nonce, OffsetDateTime seenAt) {
            return seen.add(tenantId + ":" + nonce);
        }
        @Override public int purgeOlderThan(String tenantId, OffsetDateTime cutoff) { return 0; }
    }

    private static CallbackMetadata metadataWithNonce(String nonce) {
        return new CallbackMetadata("worker_a", 1, "worker_a:task_01:1", "PATCH", "req-1", "trace-1",
            OffsetDateTime.parse("2026-06-12T10:00:00Z"), nonce, "idem-1", "hmac-sha256=x", "/internal/x", "hash");
    }

    @Test
    void replayedNonceIsRejectedWith401StyleAuthException() {
        CallbackReplayGuard guard = new CallbackReplayGuard(new InMemoryNonceStore());
        assertThatCode(() -> guard.requireFreshNonce("tenant_01", metadataWithNonce("n1"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireFreshNonce("tenant_01", metadataWithNonce("n1")))
            .isInstanceOf(CallbackAuthException.class);
    }
}
```

Plus one heartbeat case appended to `LeaseClaimOnCallbackTest` exercising the app service: arrange a RUNNING task whose `ASR` step is at progress 40 (via `updateWorkerStep(...RUNNING, 40...)` after claim), send a heartbeat with progress 5, assert the returned DTO still shows step progress 40 **and** `task.leaseExpiresAt()` moved to `heartbeatAt + LEASE_TTL` (lease renewed despite suppressed progress).

- [ ] **Step 6: Run + commit** — `./mvnw -pl meeting-api-start -am test -Dtest='CallbackReplayGuardTest,LeaseClaimOnCallbackTest'`, then `git add -A && git commit -m "fix(api): callback nonce dedup store + monotonic heartbeat with lease renewal (I6,D4)"`

## Task 8: I7 (D5) — validate expectedInputVersion on transcript / embeddings callbacks

`expectedInputVersionForMeeting` is emitted into every task message but never validated on the way back. The `processing_tasks.expected_input_version` column exists but is not persisted by the repository; rather than widening the aggregate, read the authoritative value from the archived task message (`TaskMessageArchive`, Task 6 — committed in the same TX as task creation). Mismatch → 409 `INPUT_VERSION_CONFLICT` (registered in Task 1).

**Files:**
- Modify: `app/task/ProcessingTaskCallbackApplicationService.java`, `app/rag/EmbeddingsCallbackApplicationService.java`
- Create test: `ExpectedInputVersionTest.java`

- [ ] **Step 1: Implement.** Inject `TaskMessageArchive taskMessageArchive` (nullable for legacy ctors) into both services. Shared helper in each service:

```java
@SuppressWarnings("unchecked")
private Map<String, Object> expectedInputVersion(String tenantId, String taskId) {
    if (taskMessageArchive == null) return Map.of();
    return taskMessageArchive.latestTaskMessagePayload(tenantId, taskId)
        .map(payload -> payload.get("expectedInputVersion"))
        .filter(Map.class::isInstance)
        .map(value -> (Map<String, Object>) value)
        .orElse(Map.of());
}
```

In `writeTranscript`, after the meeting-linkage check (before the version-increment check):

```java
Object expectedTranscript = expectedInputVersion(command.tenantId(), command.taskId()).get("transcriptVersion");
if (expectedTranscript instanceof Number expected) {
    int current = transcriptRepository.currentTranscriptVersion(command.tenantId(), command.meetingId());
    if (expected.intValue() != current) {
        throw new ApplicationException(ErrorCode.INPUT_VERSION_CONFLICT, 409,
            "task expected transcriptVersion=" + expected.intValue() + " but current is " + current
                + " — transcript changed while the task was in flight", false);
    }
}
```

In `EmbeddingsCallbackApplicationService.writeInTransaction`, after the attempt check:

```java
Object expectedStrategy = expectedInputVersion(command.tenantId(), command.taskId()).get("chunkStrategyVersion");
if (expectedStrategy != null && !String.valueOf(expectedStrategy).equals(command.chunkStrategyVersion())) {
    throw new ApplicationException(ErrorCode.INPUT_VERSION_CONFLICT, 409,
        "task expected chunkStrategyVersion=" + expectedStrategy + " but callback carries "
            + command.chunkStrategyVersion(), false);
}
```

- [ ] **Step 2: Test** (`ExpectedInputVersionTest.java`) — arrange the callback service with a `TaskMessageArchive` stub returning `Map.of("expectedInputVersion", Map.of("transcriptVersion", 0, "chunkStrategyVersion", "v1"))`, a transcript repo reporting `currentTranscriptVersion = 3` (simulating a mid-flight edit), and assert `writeTranscript` throws `ApplicationException` with `errorCode() == ErrorCode.INPUT_VERSION_CONFLICT`; second case: embeddings callback with `chunkStrategyVersion="v2"` against expected `"v1"` → same. Third case: matching versions pass through to the normal idempotency/persist path (use the in-memory stubs from `LeaseClaimOnCallbackTest`).

- [ ] **Step 3: Run + commit** — `./mvnw -pl meeting-api-start -am test -Dtest=ExpectedInputVersionTest`, then `git add -A && git commit -m "fix(api): enforce expectedInputVersion on transcript/embeddings callbacks (I7,D5)"`

## Task 9: I8 (D6) — implement POST /internal/processing-tasks/{taskId}/artifacts for real

The endpoint currently builds metadata, never verifies it, persists nothing, and answers `{"accepted": true}`. Implement the full validation chain (HMAC → skew → nonce → claim/cancel gate → linkage → Idempotency-Key) and persist via `ArtifactManifestRepository`. **P1 coordination:** the worker sends top-level `artifactManifestId = "artifact_manifest_{taskId}_{attemptNo}"` and later references that exact id in `/transcript` — persist the manifest row under that id. Contract shape (`internal-callback-api.yaml` `ArtifactCallbackRequest`, verified): required `tenantId, taskId, attemptNo, artifacts[]`; each artifact requires `artifactType, artifactUri (^tos://.+), sha256`, optional `sizeBytes`, `metadata`; optional `meetingId`, `artifactManifestId`.

**Files:**
- Create: `meeting-api-client/src/main/java/com/meeting/api/client/internal/callback/ArtifactsCallbackCommand.java`
- Modify: `app/task/ProcessingTaskCallbackApplicationService.java`, `adapter/internal/ProcessingTaskCallbackController.java`
- Create test: `ArtifactsCallbackTest.java`

- [ ] **Step 1: Command DTO** (client module):

```java
package com.meeting.api.client.internal.callback;

import java.util.List;
import java.util.Map;

public record ArtifactsCallbackCommand(
    CallbackMetadata metadata,
    String tenantId,
    String meetingId,
    String taskId,
    int attemptNo,
    List<Artifact> artifacts,
    String artifactManifestId
) {
    public record Artifact(
        String artifactType,
        String artifactUri,
        String sha256,
        Long sizeBytes,
        Map<String, Object> artifactMetadata
    ) {
    }
}
```

- [ ] **Step 2: App handler** in `ProcessingTaskCallbackApplicationService` (inject `ArtifactManifestRepository artifactManifestRepository` + `ObjectMapper objectMapper`, nullable on legacy ctors):

```java
public Map<String, Object> registerArtifacts(ArtifactsCallbackCommand command) {
    securityVerifier.verify(command.metadata());
    if (command.artifacts() == null || command.artifacts().isEmpty()) {
        throw new IllegalArgumentException("artifacts must not be empty");
    }
    return tenantScopedTransaction.execute(command.tenantId(), null, command.metadata().requestId(), () -> {
        ProcessingTask task = load(command.tenantId(), command.taskId());
        if (replayGuard != null) replayGuard.requireFreshNonce(command.tenantId(), command.metadata());
        requireCallbackMeetingMatchesTask(command.meetingId(), task);
        claimLeaseForCallback(task, command.attemptNo(), command.metadata(), OffsetDateTime.now(clock));
        String manifestId = command.artifactManifestId() == null || command.artifactManifestId().isBlank()
            ? "artifact_manifest_" + command.taskId() + "_" + command.attemptNo()
            : command.artifactManifestId();
        if (!persistCallbackEvent(command.tenantId(), command.taskId(), command.metadata(), 200, null)) {
            taskRepository.save(task);   // replay: lease renewal still persists
            return Map.of("accepted", true, "taskId", command.taskId(), "callback", "ARTIFACTS",
                "artifactManifestId", manifestId, "replayed", true);
        }
        ArtifactsCallbackCommand.Artifact first = command.artifacts().get(0);
        artifactManifestRepository.save(new ArtifactManifestRepository.ArtifactManifestRecord(
            manifestId,
            command.tenantId(),
            task.meetingId(),
            command.taskId(),
            command.artifacts().size() == 1 ? first.artifactType() : "WORKER_ARTIFACT_BATCH",
            first.artifactUri(),
            first.sha256(),
            null,
            toJson(Map.of("attemptNo", command.attemptNo(), "workerId", command.metadata().workerId())),
            toJson(Map.of("artifacts", command.artifacts())),
            "[]",
            null, null, "ai-worker", null, null, null, null,
            OffsetDateTime.now(clock)
        ));
        taskRepository.save(task);
        return Map.of("accepted", true, "taskId", command.taskId(), "callback", "ARTIFACTS",
            "artifactManifestId", manifestId, "replayed", false);
    });
}

private String toJson(Object value) {
    try {
        return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
        throw new IllegalArgumentException("artifact payload is not serializable", e);
    }
}
```

- [ ] **Step 3: Controller** — replace the lying handler:

```java
@PostMapping("/artifacts")
public ApiResponse<Map<String, Object>> artifacts(@PathVariable String taskId, @RequestBody String rawBody, HttpServletRequest request) {
    Map<String, Object> payload = parseBody(rawBody);
    CallbackMetadata metadata = metadata(request, rawBody);
    metrics.callbackCounter("artifacts", "ARTIFACTS").increment();
    return ApiResponse.ok(callbackApplicationService.registerArtifacts(new ArtifactsCallbackCommand(
        metadata,
        requiredString(payload, "tenantId"),
        optionalString(payload, "meetingId"),
        taskId,
        optionalInt(payload, "attemptNo", metadata.attemptNo()),
        parseArtifacts(payload.get("artifacts")),
        optionalString(payload, "artifactManifestId")
    )), metadata.requestId(), metadata.traceId());
}

private static List<ArtifactsCallbackCommand.Artifact> parseArtifacts(Object raw) {
    if (!(raw instanceof List<?> values) || values.isEmpty()) {
        throw new IllegalArgumentException("artifacts must be a non-empty array");
    }
    return values.stream()
        .filter(Map.class::isInstance)
        .map(Map.class::cast)
        .map(value -> new ArtifactsCallbackCommand.Artifact(
            requiredString(value, "artifactType"),
            requiredString(value, "artifactUri"),
            requiredString(value, "sha256"),
            value.get("sizeBytes") instanceof Number n ? n.longValue() : null,
            parseObject(value.get("metadata"))
        ))
        .toList();
}
```

- [ ] **Step 4: Test** (`ArtifactsCallbackTest.java`) — using the in-memory stubs + `NoopVerifier` pattern from `LeaseClaimOnCallbackTest`: (a) a valid call persists one manifest row whose id is `artifact_manifest_task_01_1` and whose `artifactUri` is the first artifact's (capture via an in-memory `ArtifactManifestRepository` recording saves); (b) replaying the same Idempotency-Key returns `replayed=true` and does **not** save a second row; (c) a call with a bad signature throws `CallbackAuthException` before any persistence (real `CallbackSecurityVerifier` with wrong signature). Run `-Dtest=ArtifactsCallbackTest` → pass.

- [ ] **Step 5: Commit** — `git add -A && git commit -m "fix(api): /internal artifacts callback verifies, claims lease, and persists manifests (I8,D6)"`

## Task 10: I9 (D7) — embeddings callback: task-type, lease, and chunk-ownership validation

`EmbeddingsCallbackApplicationService.writeInTransaction` checks only `attemptNo` — any valid-HMAC caller can overwrite arbitrary tenant chunk vectors via an unrelated task id. Mirror `SpeakerEnrollmentCallbackApplicationService`'s checks. (Attempt/lease/nonce/expected-version pieces already landed in Tasks 5–8; this task adds the task-type gate and chunk ownership.)

**Files:**
- Modify: `domain/rag/KnowledgeChunkRepository.java`, `infrastructure/persistence/rag/JdbcKnowledgeChunkRepository.java`, `app/rag/EmbeddingsCallbackApplicationService.java`
- Create test: `EmbeddingsCallbackHardeningTest.java`

- [ ] **Step 1: Ownership port.** Add to `KnowledgeChunkRepository`:

```java
/** Owner resolution for callback linkage checks (D7). Unknown ids are simply absent from the result. */
default List<ChunkOwner> findOwners(String tenantId, java.util.Collection<String> chunkIds) {
    throw new UnsupportedOperationException("findOwners is not implemented");
}

record ChunkOwner(String chunkId, String meetingId, String documentId) {}
```

JDBC impl in `JdbcKnowledgeChunkRepository`:

```java
@Override
public List<ChunkOwner> findOwners(String tenantId, java.util.Collection<String> chunkIds) {
    if (chunkIds.isEmpty()) return List.of();
    String placeholders = String.join(",", java.util.Collections.nCopies(chunkIds.size(), "?"));
    List<Object> args = new java.util.ArrayList<>();
    args.add(tenantId);
    args.addAll(chunkIds);
    return jdbcTemplate.query(
        "SELECT id, meeting_id, document_id FROM knowledge_chunks WHERE tenant_id = ? AND id IN (" + placeholders + ")",
        (rs, rowNum) -> new ChunkOwner(rs.getString("id"), rs.getString("meeting_id"), rs.getString("document_id")),
        args.toArray());
}
```

- [ ] **Step 2: Service hardening.** In `writeInTransaction`, after the claim/attempt/expected-version checks and before `markEmbeddings`:

```java
private static final java.util.Set<String> EMBEDDING_TASK_TYPES =
    java.util.Set.of("TEXT_EMBEDDING", "MEETING_FULL_PIPELINE", "RAG_REINDEX");

if (!EMBEDDING_TASK_TYPES.contains(task.taskType())) {
    throw new ApplicationException(ErrorCode.INVALID_TASK_MESSAGE, 422,
        "task type " + task.taskType() + " does not accept embeddings callbacks", false);
}
// Chunk ownership: every chunk id must exist and belong to the task's scope.
Map<String, Object> archived = taskMessageArchive == null ? Map.of()
    : taskMessageArchive.latestTaskMessagePayload(command.tenantId(), command.taskId()).orElse(Map.of());
String scopeMeetingId = task.meetingId() != null ? task.meetingId()
    : (archived.get("meetingId") == null ? null : String.valueOf(archived.get("meetingId")));
String scopeDocumentId = archived.get("documentId") == null ? null : String.valueOf(archived.get("documentId"));
List<String> requestedChunkIds = command.items().stream().map(EmbeddingsCallbackCommand.Item::chunkId).toList();
Map<String, KnowledgeChunkRepository.ChunkOwner> owners = knowledgeChunkRepository
    .findOwners(command.tenantId(), requestedChunkIds).stream()
    .collect(java.util.stream.Collectors.toMap(KnowledgeChunkRepository.ChunkOwner::chunkId, o -> o));
for (String chunkId : requestedChunkIds) {
    KnowledgeChunkRepository.ChunkOwner owner = owners.get(chunkId);
    if (owner == null) {
        throw new IllegalArgumentException("unknown chunk id in embeddings callback: " + chunkId);
    }
    boolean meetingMatch = scopeMeetingId != null && scopeMeetingId.equals(owner.meetingId());
    boolean documentMatch = scopeDocumentId != null && scopeDocumentId.equals(owner.documentId());
    if (!meetingMatch && !documentMatch) {
        throw new IllegalStateException("chunk " + chunkId + " does not belong to task " + command.taskId() + " scope");
    }
}
```

- [ ] **Step 3: Test** (`EmbeddingsCallbackHardeningTest.java`) — in-memory `KnowledgeChunkRepository` overriding `findOwners` + `markEmbeddings`: (a) a `SPEAKER_ENROLLMENT`-typed task gets 422 `INVALID_TASK_MESSAGE`; (b) a chunk owned by a different meeting throws `IllegalStateException` and `markEmbeddings` is never reached; (c) a meeting-scoped batch whose chunks match `task.meetingId()` persists (`updated == requested`). Run `-Dtest=EmbeddingsCallbackHardeningTest` → pass; also re-run `EmbeddingsCallbackApplicationServiceTest`.

- [ ] **Step 4: Commit** — `git add -A && git commit -m "fix(api): embeddings callback task-type + chunk-ownership validation (I9,D7)"`

## Task 11: I12 (D8) — close meetings.status when the owning task reaches TERMINAL

Verified: only `CREATED→PROCESSING` (at audio-upload task creation) and delete→`DELETED` exist; meetings stay PROCESSING forever. Real enum values: `SUCCEEDED`/`FAILED` (no COMPLETED). **Design deviation from D8's "outbox listener path", documented:** closure happens in the same transaction as the terminal task transition via a small app service — no new event type, atomic with the task write, trivially testable; the existing SSE/outbox events remain the notification path. Mapping (P1: PARTIAL_SUCCEEDED is the *normal* outcome): task `SUCCEEDED|PARTIAL_SUCCEEDED → MeetingStatus.SUCCEEDED`; `FAILED|CANCELLED → MeetingStatus.FAILED`; only meetings currently `PROCESSING` are touched (never DELETED); only `MEETING_FULL_PIPELINE` tasks close their meeting (meeting-scoped TEXT_EMBEDDING tasks must not).

**Files:**
- Create: `app/task/MeetingStatusClosureService.java`
- Modify: `app/task/TaskStepProgressService.java`, `app/task/ProcessingTaskCallbackApplicationService.java` (fail path), `app/task/ProcessingTaskLeaseScanner.java` (cancel confirm), `app/task/ProcessingTaskRequeueService.java` (retry exhausted)
- Create test: `MeetingStatusClosureTest.java`

- [ ] **Step 1: Service.**

```java
package com.meeting.api.app.task;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.domain.meeting.MeetingRepository;
import com.meeting.api.domain.task.ProcessingTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * D8: when a MEETING_FULL_PIPELINE task lands TERMINAL, fold the outcome into
 * meetings.status. MUST be called inside the same tenant-scoped transaction
 * that persisted the terminal task state.
 */
@Service
public class MeetingStatusClosureService {
    private static final Logger log = LoggerFactory.getLogger(MeetingStatusClosureService.class);
    private final MeetingRepository meetingRepository;

    public MeetingStatusClosureService(MeetingRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
    }

    public void closeForTerminalTask(ProcessingTask task) {
        if (!"MEETING_FULL_PIPELINE".equals(task.taskType()) || task.meetingId() == null) {
            return;
        }
        MeetingStatus target = switch (task.status()) {
            case SUCCEEDED, PARTIAL_SUCCEEDED -> MeetingStatus.SUCCEEDED;
            case FAILED, CANCELLED -> MeetingStatus.FAILED;
            default -> null;
        };
        if (target == null) {
            return;
        }
        meetingRepository.findById(task.tenantId(), task.meetingId())
            .filter(meeting -> meeting.status() == MeetingStatus.PROCESSING)
            .ifPresent(meeting -> {
                meetingRepository.updateStatus(task.tenantId(), task.meetingId(), target);
                log.info("meeting_status_closed tenant={} meeting={} task={} status={}",
                    task.tenantId(), task.meetingId(), task.taskId(), target);
            });
    }
}
```

- [ ] **Step 2: Call sites** (each is already inside a tenant TX; inject the service as a nullable collaborator on legacy ctors, `if (closure != null)` guard):
  - `TaskStepProgressService.completeJavaPhase` and `completeWithoutJavaPhase` — after `task.completeTerminal(...)`/`task.completeJavaPhase(...)` + `save`, call `closure.closeForTerminalTask(task)`.
  - `ProcessingTaskCallbackApplicationService.fail` — after `task.completeTerminal(FAILED, ...)`.
  - `ProcessingTaskLeaseScanner` cancel-confirm block (Task 6) — after `task.confirmCancelled(...)`.
  - `ProcessingTaskRequeueService.requeueOrphaned` — after both `completeTerminal(FAILED, ...)` branches.

- [ ] **Step 3: Test** (`MeetingStatusClosureTest.java`) — in-memory `MeetingRepository` (reuse the `InMemoryMeetingRepo` shape from `MinutesApplicationServiceTest`, with an `updateStatus` override recording the new status): (a) PARTIAL_SUCCEEDED full-pipeline task on a PROCESSING meeting → `SUCCEEDED`; (b) FAILED task → `FAILED`; (c) meeting-scoped `TEXT_EMBEDDING` task → untouched; (d) meeting already `DELETED` → untouched. Run `-Dtest=MeetingStatusClosureTest` → pass.

- [ ] **Step 4: Commit** — `git add -A && git commit -m "fix(api): close meetings.status on terminal MEETING_FULL_PIPELINE tasks (I12,D8)"`

## Task 12: I13 (D9) — make public Idempotency-Key enforced and honored

Adjusted finding: `POST /api/meetings` already *requires* the header but ignores it; `CreateDocumentCommand`/`CreateSpeakerProfileCommand` already carry an ignored `idempotencyKey` field; `CreateExportCommand`/`CreateMeetingCommand` lack the field; several controllers declare `required = false`. Implement reservation-style idempotency: same key + same body → replay stored response; same key + different body → 409 `IDEMPOTENCY_CONFLICT`; same key while original still in flight → 409 (retryable).

**Files:**
- Create: `V202606121010__api_idempotency_keys.sql`, `domain/idempotency/PublicIdempotencyRepository.java`, `infrastructure/persistence/idempotency/JdbcPublicIdempotencyRepository.java`, `app/common/PublicIdempotencyService.java`
- Modify: `client/meeting/CreateMeetingCommand.java` + `client/export/CreateExportCommand.java` (append `String idempotencyKey` component; update all constructions), `app/meeting/MeetingApplicationService.java`, `app/export/ExportApplicationService.java`, `app/document/DocumentApplicationService.java`, `app/speaker/SpeakerProfileApplicationService.java`, `adapter/meeting/MeetingController.java`, `adapter/export/ExportController.java`, `adapter/document/DocumentController.java`, `adapter/speaker/SpeakerProfileController.java`
- Create test: `PublicIdempotencyServiceTest.java`

- [ ] **Step 1: Migration** `V202606121010__api_idempotency_keys.sql`:

```sql
CREATE TABLE IF NOT EXISTS api_idempotency_keys (
  id text PRIMARY KEY,
  tenant_id text NOT NULL REFERENCES tenants(id),
  endpoint text NOT NULL,
  idempotency_key text NOT NULL,
  request_body_sha256 text NOT NULL,
  response_json jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT api_idempotency_keys_uk UNIQUE (tenant_id, endpoint, idempotency_key)
);
CREATE INDEX IF NOT EXISTS api_idempotency_keys_created_idx ON api_idempotency_keys (created_at);

ALTER TABLE api_idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_idempotency_keys FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON api_idempotency_keys;
CREATE POLICY tenant_isolation ON api_idempotency_keys
  USING (tenant_id = public.current_tenant_id())
  WITH CHECK (tenant_id = public.current_tenant_id());
```

- [ ] **Step 2: Port + JDBC.**

```java
package com.meeting.api.domain.idempotency;

import java.util.Optional;

public interface PublicIdempotencyRepository {
    /** @return true when the reservation was inserted (key fresh for this endpoint). */
    boolean reserve(String tenantId, String endpoint, String idempotencyKey, String requestBodySha256);

    Optional<StoredRequest> find(String tenantId, String endpoint, String idempotencyKey);

    void storeResponse(String tenantId, String endpoint, String idempotencyKey, String responseJson);

    record StoredRequest(String requestBodySha256, String responseJson) {}
}
```

`JdbcPublicIdempotencyRepository`: `reserve` = `INSERT ... (id, tenant_id, endpoint, idempotency_key, request_body_sha256) VALUES ('idem_'+uuid, ...) ON CONFLICT DO NOTHING` returning `update == 1`; `find` = `SELECT request_body_sha256, response_json::text FROM api_idempotency_keys WHERE tenant_id=? AND endpoint=? AND idempotency_key=?`; `storeResponse` = `UPDATE ... SET response_json = ?::jsonb WHERE ...`.

- [ ] **Step 3: App helper.**

```java
package com.meeting.api.app.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.domain.idempotency.PublicIdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * D9 reservation-style idempotency for public writes. The action supplier manages
 * its own tenant transaction (facades already do); reserve/replay/store each run
 * in their own short tenant TX via the repository.
 */
@Service
public class PublicIdempotencyService {
    private final PublicIdempotencyRepository repository;
    private final TenantScopedTransaction tenantTx;
    private final ObjectMapper objectMapper;

    public PublicIdempotencyService(PublicIdempotencyRepository repository, TenantScopedTransaction tenantTx, ObjectMapper objectMapper) {
        this.repository = repository;
        this.tenantTx = tenantTx;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(String tenantId, String endpoint, String idempotencyKey, Object requestBody,
                         Class<T> responseType, Supplier<T> action) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        String bodyHash = sha256(toJson(requestBody));
        boolean reserved = tenantTx.execute(tenantId, null, null,
            () -> repository.reserve(tenantId, endpoint, idempotencyKey, bodyHash));
        if (!reserved) {
            PublicIdempotencyRepository.StoredRequest existing = tenantTx.execute(tenantId, null, null,
                () -> repository.find(tenantId, endpoint, idempotencyKey))
                .orElseThrow(() -> new IdempotencyConflictException("idempotency record disappeared: " + idempotencyKey));
            if (!existing.requestBodySha256().equals(bodyHash)) {
                throw new IdempotencyConflictException("Idempotency-Key reused with a different request body");
            }
            if (existing.responseJson() == null) {
                throw new ApplicationException(ErrorCode.IDEMPOTENCY_CONFLICT, 409,
                    "original request with this Idempotency-Key is still in progress", true);
            }
            return fromJson(existing.responseJson(), responseType);
        }
        T result = action.get();
        String responseJson = toJson(result);
        tenantTx.executeWithoutResult(tenantId, null, null,
            () -> repository.storeResponse(tenantId, endpoint, idempotencyKey, responseJson));
        return result;
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("idempotency payload not serializable", e); }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try { return objectMapper.readValue(json, type); }
        catch (Exception e) { throw new IllegalStateException("stored idempotent response unreadable", e); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Wire the four write families.** Append `String idempotencyKey` to `CreateMeetingCommand` and `CreateExportCommand` (update every construction site — compiler drives the list); controllers pass the header through (`MeetingController.create` already binds it; flip `ExportController.createExport`, `DocumentController`, `SpeakerProfileController` create/enroll headers to `required = true`). In each app service, wrap the existing create body:

```java
// MeetingApplicationService.create
return publicIdempotencyService.execute(
    command.tenantId(), "POST /api/meetings", command.idempotencyKey(), command,
    MeetingDTO.class, () -> doCreate(command));   // doCreate = the current create body
```

Same pattern: `ExportApplicationService` (`"POST /api/meetings/{meetingId}/exports"`, `ExportJobDTO.class`), `DocumentApplicationService` (`"POST /api/documents"`, `DocumentDTO.class`), `SpeakerProfileApplicationService` profile + enrollment creates (`SpeakerProfileDTO.class` / `SpeakerEnrollmentDTO.class` — the commands already carry the key). `PublicIdempotencyService` is a nullable collaborator on legacy test constructors (skip wrapping when null).

- [ ] **Step 5: Test** (`PublicIdempotencyServiceTest.java`) — in-memory `PublicIdempotencyRepository` (HashMap): (a) first call executes the action and stores the response; (b) second call with same key+body returns the stored DTO **without** re-invoking the action (count invocations); (c) same key + different body → `IdempotencyConflictException`; (d) same key while response not yet stored → `ApplicationException` 409 retryable. Use `record SampleDto(String id)` as the response type. Run `-Dtest=PublicIdempotencyServiceTest` → pass; run `MeetingControllerTest` and fix command-arity fallout.

- [ ] **Step 6: Commit** — `git add -A && git commit -m "fix(api): reservation-style public Idempotency-Key on meetings/exports/documents/speaker writes (I13,D9)"`

## Task 13: I14 (D10) — RAG answer-cache: version-aware key, lookup after authorization, STALE invalidation

Verified: `toCacheKey` has no version component; `lookup` runs before any authorization; invalidation fires only from `KnowledgeChunkReindexRequestedEvent`; `TranscriptApplicationService.propagateStale` does not touch the cache, so an edited transcript keeps serving stale cached answers, and a revoked scope keeps serving until TTL.

**Files:**
- Modify: `app/rag/RagAnswerCache.java` (key gains `versionFingerprint`), `app/rag/RagQueryApplicationService.java`, `app/transcript/TranscriptApplicationService.java`
- Modify tests: `InMemoryRagAnswerCacheTest.java`, `RagQueryApplicationServiceTest.java`; Create: `RagAnswerCacheVersioningTest.java`

- [ ] **Step 1: Key shape.** Extend the record in `RagAnswerCache`:

```java
record RagCacheKey(
    String tenantId,
    String userId,
    String question,
    RagQueryScope scope,
    int topN,
    boolean includeStale,
    String versionFingerprint
) {
    public RagCacheKey {
        // existing tenantId/userId/question guards + scope canonicalisation unchanged
        versionFingerprint = versionFingerprint == null ? "" : versionFingerprint;
    }
}
```

(Compiler drives updates in `InMemoryRagAnswerCache` — none needed, key is opaque — and test constructions in `InMemoryRagAnswerCacheTest`.)

- [ ] **Step 2: Build the fingerprint inside the authorize TX and look up *after* authorization.** In `RagQueryApplicationService`, inject `MeetingRepository meetingRepository` and `@Value("${meeting.chunk.strategy-version:v1}") String chunkStrategyVersion` (append to both constructors; test ctor takes them as plain params). Restructure the head of `doQuery`/`query` (on top of Task 4's TX layout):

```java
@Override
public RagAnswerDTO query(RagQueryCommand command) {
    // Short TX: authorize scope + load version fingerprint for the authorized meetings.
    record AuthorizedContext(RetrievalScope scope, String fingerprint) {}
    AuthorizedContext ctx = inShortTx(command, () -> {
        RetrievalScope authorized = authorizationService.authorizeScope(
            command.tenantId(), command.userId(), toRetrievalScope(command.scope()));
        StringBuilder fp = new StringBuilder("cs:").append(chunkStrategyVersion);
        for (String meetingId : authorized.meetingIds().stream().sorted().toList()) {
            meetingRepository.findById(command.tenantId(), meetingId).ifPresent(meeting ->
                fp.append(";m:").append(meetingId)
                  .append(":t").append(meeting.transcriptVersion())
                  .append(":mv").append(meeting.minutesVersion()));
        }
        return new AuthorizedContext(authorized, fp.toString());
    });
    if (!command.scope().isEmpty() && ctx.scope().isEmpty()) {
        log.info("rag_query_unauthorized_scope tenant={} user={} ...", command.tenantId(), command.userId());
        return degraded(DEGRADED_ANSWER_NO_CHUNKS);   // fail closed BEFORE any cache read
    }
    RagAnswerCache.RagCacheKey cacheKey = toCacheKey(command, ctx.fingerprint());
    var cached = answerCache.lookup(cacheKey);
    if (cached.isPresent()) { /* unchanged log */ return cached.get(); }
    RagAnswerDTO answer = doQuery(command, ctx.scope());
    if (answer.artifactManifestId() != null) {
        answerCache.store(cacheKey, answer, coverageOf(answer));
    }
    return answer;
}
```

`doQuery` drops its own authorize phase and takes the pre-authorized `RetrievalScope` parameter; `toCacheKey(command, fingerprint)` passes the new component. Transcript or minutes regeneration now changes the fingerprint, so stale keys simply miss.

- [ ] **Step 3: STALE cascade invalidation.** In `TranscriptApplicationService`, add a nullable `RagAnswerCache ragAnswerCache` collaborator (widest ctor; legacy ctors pass null) and extend `propagateStale`:

```java
private void propagateStale(String tenantId, String meetingId) {
    minutesRepository.markStale(tenantId, meetingId);
    actionItemRepository.markStaleForMeeting(tenantId, meetingId);
    decisionRepository.markStaleForMeeting(tenantId, meetingId);
    riskRepository.markStaleForMeeting(tenantId, meetingId);
    knowledgeChunkRepository.markStaleForMeeting(tenantId, meetingId);
    if (ragAnswerCache != null) {
        int dropped = ragAnswerCache.invalidateMeeting(tenantId, meetingId);
        if (dropped > 0) {
            log.info("rag_cache_invalidated_on_transcript_edit tenant={} meeting={} dropped={}", tenantId, meetingId, dropped);
        }
    }
}
```

- [ ] **Step 4: Test** (`RagAnswerCacheVersioningTest.java`): (a) two `RagCacheKey`s differing only in `versionFingerprint` hit different entries in a real `InMemoryRagAnswerCache`; (b) `RagQueryApplicationService` (mocks as in `LlmCallsOutsideTransactionTest`, plus a `MeetingRepository` returning a meeting with `transcriptVersion=1`) stores under a key embedding `t1`, then after the mock returns `transcriptVersion=2` a second identical query **misses** (embed gateway invoked twice — count); (c) cache `lookup` is not called when an explicitly-scoped query authorizes to empty (mock `RagAnswerCache`, `verify(cache, never()).lookup(any())`). Run `-Dtest='RagAnswerCacheVersioningTest,InMemoryRagAnswerCacheTest,RagQueryApplicationServiceTest'` → pass (update existing key constructions with a `""` fingerprint).

- [ ] **Step 5: Commit** — `git add -A && git commit -m "fix(api): version-fingerprinted RAG answer cache, post-authz lookup, STALE invalidation (I14,D10)"`

## Task 14: I15 (D12) — outbox per-aggregate ordering fence

Verified: `markFailed` flips an event back to PENDING while later events of the same aggregate can publish (same batch continues; SKIP LOCKED lets a second instance publish a later event while the earlier one is locked). Fence at both levels. Note on status values: failed-but-retryable rows are status `PENDING` (with retry_count bumped); `DLQ` is terminal and must **not** block the aggregate forever (ops intervention path) — document this in the SQL comment.

**Files:**
- Modify: `infrastructure/mq/OutboxEventStore.java` (lockPendingBatch SQL), `infrastructure/mq/OutboxPublisher.java` (in-batch fence)
- Create test: `OutboxPerAggregateFencingTest.java`

- [ ] **Step 1: SQL fence** — in `lockPendingBatch`:

```sql
SELECT id, tenant_id, aggregate_type, aggregate_id, sequence_no,
       event_type, payload_json::text, dedupe_key, retry_count, created_at
  FROM domain_events_outbox o
 WHERE o.tenant_id = ?
   AND o.status = 'PENDING'
   -- Per-aggregate fence (D12): never hand out an event while an earlier
   -- sequence of the same aggregate is still PENDING (including rows
   -- currently locked by another instance). DLQ rows are terminal and do
   -- not block — unwedging them is an ops action.
   AND NOT EXISTS (
         SELECT 1
           FROM domain_events_outbox earlier
          WHERE earlier.tenant_id = o.tenant_id
            AND earlier.aggregate_type = o.aggregate_type
            AND earlier.aggregate_id = o.aggregate_id
            AND earlier.sequence_no < o.sequence_no
            AND earlier.status = 'PENDING'
       )
 ORDER BY aggregate_type, aggregate_id, sequence_no
 LIMIT ?
   FOR UPDATE OF o SKIP LOCKED
```

- [ ] **Step 2: In-batch fence** — in `OutboxPublisher.publishPending`, track aggregates that failed within this batch and skip their later events:

```java
java.util.Set<String> blockedAggregates = new java.util.HashSet<>();
for (OutboxEventRecord record : outboxEventStore.lockPendingBatch(tenantId, batchSize)) {
    String aggregateKey = record.tenantId() + "|" + record.aggregateType() + "|" + record.aggregateId();
    if (blockedAggregates.contains(aggregateKey)) {
        continue;   // earlier sequence of this aggregate just failed — keep intra-aggregate order
    }
    // ... existing SKIPPED / unroutable handling unchanged ...
    try {
        // ... existing preflight + publish + markPublished ...
    } catch (ExportJobMessageValidator.InvalidPayloadException | ProcessingTaskMessageValidator.InvalidPayloadException ex) {
        outboxEventStore.markFailed(record.id(), "OUTBOX_PUBLISH_FAILED", "schema violation: " + ex.getMessage(), maxRetries);
        metrics.outboxFailedCounter(record.eventType(), "OUTBOX_PUBLISH_FAILED").increment();
        blockedAggregates.add(aggregateKey);
    } catch (Exception ex) {
        outboxEventStore.markFailed(record.id(), "OUTBOX_PUBLISH_FAILED", ex.getMessage(), maxRetries);
        metrics.outboxFailedCounter(record.eventType(), "OUTBOX_PUBLISH_FAILED").increment();
        blockedAggregates.add(aggregateKey);
    }
}
```

- [ ] **Step 3: Test** (`OutboxPerAggregateFencingTest.java`) — Mockito-mock `OutboxEventStore` + `RabbitMqPublisher`: batch of three `ProcessingTaskCreatedEvent` records (`agg A seq1`, `agg A seq2`, `agg B seq1`, with valid task-message payloads so preflight passes); `rabbitMqPublisher.publish` throws on the first call only. Assert: `markFailed` called for A-seq1; `markPublished` **never** called for A-seq2 (skipped); B-seq1 published. SQL-level fence is covered by the existing outbox IT if present; otherwise rely on ddl-unchanged + this unit test (the NOT EXISTS clause is exercised in the Task 16 RLS IT environment where migrations run). Run `-Dtest='OutboxPerAggregateFencingTest,OutboxPublisherRoutingTest,ProcessingTaskMessagePublisherTest'` → pass.

- [ ] **Step 4: Commit** — `git add -A && git commit -m "fix(api): per-aggregate outbox publish fencing under retry and multi-instance (I15,D12)"`

## Task 15: P3 gap — `POST /api/auth/refresh` with HttpOnly refresh cookie + CSRF double-submit

Backend gap found by the meeting-web plan: SPEC §5.2 mandates single-flight 401 refresh, but no refresh endpoint exists (public-api.yaml has only login/logout/me; verified `AuthController` + `InMemoryAuthApplicationService`). **P3 is already coded against:** `POST /api/auth/refresh`, CSRF cookie named `XSRF-TOKEN`, header `X-CSRF-Token` — match exactly. Refresh token lives in an HttpOnly cookie `meeting_refresh_token` scoped to `Path=/api/auth`; rotated on every refresh (one-time use); revoked on logout.

**Files:**
- Modify: `packages/meeting-contracts/openapi/public-api.yaml`, `client/auth/AuthFacade.java`, `app/auth/InMemoryAuthApplicationService.java`, `adapter/auth/AuthController.java`
- Create: `client/auth/LoginSessionDTO.java`; test `AuthRefreshTest.java`

- [ ] **Step 1: Contract.** Add to `public-api.yaml` under `paths:` (next to `/auth/login`):

```yaml
  /auth/refresh:
    post:
      operationId: refreshAccessToken
      summary: Exchange the HttpOnly refresh cookie for a new access token
      description: >
        Requires the meeting_refresh_token HttpOnly cookie (set by /auth/login) and
        double-submit CSRF: the X-CSRF-Token header must match the session's CSRF
        token issued in the XSRF-TOKEN cookie. Rotates both cookies on success.
      tags: [Auth]
      security: []
      parameters:
        - name: X-CSRF-Token
          in: header
          required: true
          schema: {type: string}
      responses:
        '200':
          description: New access token issued; Set-Cookie rotates meeting_refresh_token and XSRF-TOKEN.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/LoginResponse'
        '401':
          description: Missing/expired/rotated refresh cookie or CSRF mismatch.
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
```

(Reuse the existing login response/error component names in that file — check the `/auth/login` 200 schema ref and point at the same one.) Run `cd packages/meeting-contracts && npm run check && npm run codegen`, commit drift.

- [ ] **Step 2: Facade + DTO.** `client/auth/LoginSessionDTO.java`:

```java
package com.meeting.api.client.auth;

import java.time.OffsetDateTime;

/** Adapter-facing login/refresh result. refreshToken/csrfToken travel ONLY as cookies — never in a JSON body. */
public record LoginSessionDTO(
    LoginResultDTO result,
    String refreshToken,
    OffsetDateTime refreshExpiresAt,
    String csrfToken
) {
}
```

`AuthFacade` gains:

```java
LoginSessionDTO loginSession(LoginCommand command);

/** @return rotated session; throws IllegalArgumentException on unknown/expired token or CSRF mismatch (→401 in adapter). */
LoginSessionDTO refreshSession(String refreshToken, String csrfToken);

void revokeRefresh(String refreshToken);
```

- [ ] **Step 3: In-memory implementation** (`InMemoryAuthApplicationService`) — add:

```java
private static final java.time.Duration REFRESH_TTL = java.time.Duration.ofDays(7);
private final Map<String, RefreshSession> refreshSessions = new ConcurrentHashMap<>();
private final java.security.SecureRandom random = new java.security.SecureRandom();

private record RefreshSession(AuthUserDTO user, String csrfToken, OffsetDateTime expiresAt) {}

private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return java.util.HexFormat.of().formatHex(bytes);
}

@Override
public LoginSessionDTO loginSession(LoginCommand command) {
    LoginResultDTO result = login(command);   // existing credential check + access token mint
    String refreshToken = randomToken();
    String csrfToken = randomToken();
    OffsetDateTime refreshExpiresAt = OffsetDateTime.now(clock).plus(REFRESH_TTL);
    refreshSessions.put(refreshToken, new RefreshSession(result.user(), csrfToken, refreshExpiresAt));
    return new LoginSessionDTO(result, refreshToken, refreshExpiresAt, csrfToken);
}

@Override
public LoginSessionDTO refreshSession(String refreshToken, String csrfToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
        throw new IllegalArgumentException("refresh token missing");
    }
    RefreshSession session = refreshSessions.remove(refreshToken);   // one-time use: rotate on every refresh
    if (session == null || !session.expiresAt().isAfter(OffsetDateTime.now(clock))) {
        throw new IllegalArgumentException("refresh token unknown or expired");
    }
    if (csrfToken == null || !java.security.MessageDigest.isEqual(
            session.csrfToken().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            csrfToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
        throw new IllegalArgumentException("csrf token mismatch");
    }
    OffsetDateTime accessExpiresAt = OffsetDateTime.now(clock).plus(TOKEN_TTL);
    String accessToken = jwtCodec.encode(session.user(), accessExpiresAt);
    sessions.put(accessToken, new Session(session.user(), accessExpiresAt));
    LoginResultDTO result = new LoginResultDTO(accessToken, accessExpiresAt, session.user());
    String newRefresh = randomToken();
    String newCsrf = randomToken();
    OffsetDateTime refreshExpiresAt = OffsetDateTime.now(clock).plus(REFRESH_TTL);
    refreshSessions.put(newRefresh, new RefreshSession(session.user(), newCsrf, refreshExpiresAt));
    return new LoginSessionDTO(result, newRefresh, refreshExpiresAt, newCsrf);
}

@Override
public void revokeRefresh(String refreshToken) {
    if (refreshToken != null) refreshSessions.remove(refreshToken);
}
```

- [ ] **Step 4: Controller.** In `AuthController` (use `org.springframework.http.ResponseCookie`; add `@Value("${meeting.auth.cookie-secure:false}") boolean cookieSecure` constructor param — set `true` in the prod profile):

```java
@PostMapping("/login")
public ResponseEntity<ApiResponse<LoginResultDTO>> login(
    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
    @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
    @RequestBody LoginRequest request
) {
    LoginSessionDTO session = authFacade.loginSession(
        new LoginCommand(request.username(), request.password(), requestId, traceId));
    return withSessionCookies(session, ApiResponse.ok(session.result(), requestId, traceId));
}

@PostMapping("/refresh")
public ResponseEntity<ApiResponse<LoginResultDTO>> refresh(
    @CookieValue(value = "meeting_refresh_token", required = false) String refreshCookie,
    @RequestHeader(value = "X-CSRF-Token", required = false) String csrfHeader,
    @RequestHeader(value = "X-Request-Id", required = false) String requestId,
    @RequestHeader(value = "X-Trace-Id", required = false) String traceId
) {
    try {
        LoginSessionDTO session = authFacade.refreshSession(refreshCookie, csrfHeader);
        return withSessionCookies(session, ApiResponse.ok(session.result(), requestId, traceId));
    } catch (IllegalArgumentException ex) {
        return ResponseEntity.status(401).body(ApiResponse.failed(
            ErrorInfo.of(ErrorCode.AUTH_REQUIRED, "refresh rejected: " + ex.getMessage(), false),
            requestId, traceId));
    }
}

private ResponseEntity<ApiResponse<LoginResultDTO>> withSessionCookies(LoginSessionDTO session, ApiResponse<LoginResultDTO> body) {
    ResponseCookie refresh = ResponseCookie.from("meeting_refresh_token", session.refreshToken())
        .httpOnly(true).secure(cookieSecure).sameSite("Strict").path("/api/auth")
        .maxAge(java.time.Duration.between(java.time.OffsetDateTime.now(), session.refreshExpiresAt()))
        .build();
    ResponseCookie csrf = ResponseCookie.from("XSRF-TOKEN", session.csrfToken())
        .httpOnly(false).secure(cookieSecure).sameSite("Strict").path("/")
        .maxAge(java.time.Duration.between(java.time.OffsetDateTime.now(), session.refreshExpiresAt()))
        .build();
    return ResponseEntity.ok()
        .header(org.springframework.http.HttpHeaders.SET_COOKIE, refresh.toString())
        .header(org.springframework.http.HttpHeaders.SET_COOKIE, csrf.toString())
        .body(body);
}
```

`logout` additionally takes `@CookieValue(value = "meeting_refresh_token", required = false) String refreshCookie`, calls `authFacade.revokeRefresh(refreshCookie)`, and clears both cookies (`maxAge(0)` variants of the two `ResponseCookie`s). Update imports (`ErrorInfo`/`ErrorCode` already referenced fully-qualified in `me` — normalize to imports).

- [ ] **Step 5: Test** (`AuthRefreshTest.java`) — drive `InMemoryAuthApplicationService` directly with a fixed clock: (a) `loginSession` returns distinct refresh + csrf tokens; (b) `refreshSession(refresh, csrf)` returns a *new* access token and *new* refresh token, and replaying the old refresh token throws `IllegalArgumentException` (rotation); (c) wrong csrf throws; (d) `revokeRefresh` then refresh throws; (e) expired refresh (advance a mutable clock past 7 days) throws. Run `-Dtest=AuthRefreshTest` → pass.

- [ ] **Step 6: Commit** — `git add -A && git commit -m "fix(api): POST /api/auth/refresh with rotating HttpOnly cookie + XSRF-TOKEN double submit (P3 gap)"`

## Task 16: D11 — non-superuser RLS integration test (regression for C3)

C3 was masked because both Testcontainers (`POSTGRES_USER`) and the compose `meeting` user are superusers (bypass RLS). Add an IT that runs the callback→LLM-phase flow as a `NOSUPERUSER NOBYPASSRLS` role and asserts SUMMARY/EXTRACTION actually execute. Keep all existing superuser ITs intact.

**Files:**
- Create test: `meeting-api-start/src/test/java/com/meeting/api/CallbackLlmPhaseRlsIT.java`

- [ ] **Step 1: Write the IT** (pattern copied from `JdbcProcessingTaskRepositoryIT`: PER_CLASS lifecycle, `TestcontainersDockerPreflight.assumeDockerAvailable()`, Flyway migrate as superuser, then connect as the restricted role):

```java
package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.extraction.ExtractionApplicationService;
import com.meeting.api.app.minutes.MinutesApplicationService;
import com.meeting.api.app.task.JavaLlmPhaseOrchestrator;
import com.meeting.api.app.task.TaskStepProgressService;
import com.meeting.api.app.task.WorkerPhaseCompletedListener;
import com.meeting.api.client.enums.ProcessingStep;
import com.meeting.api.client.enums.ProcessingTaskPhase;
import com.meeting.api.client.enums.ProcessingTaskStatus;
import com.meeting.api.client.enums.StepStatus;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.WorkerPhaseCompletedEvent;
import com.meeting.api.infrastructure.persistence.task.JdbcProcessingTaskRepository;
import com.meeting.api.infrastructure.tenant.TenantSessionContext;
import com.meeting.api.infrastructure.tenant.TenantTransactionTemplate;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CallbackLlmPhaseRlsIT {
    private static final String TENANT = "tenant_rls_it";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-12T10:00:00Z");

    private PostgreSQLContainer<?> postgres;
    private SingleConnectionDataSource superDs;
    private SingleConnectionDataSource appDs;
    private JdbcProcessingTaskRepository repo;
    private TenantScopedTransaction tenantTx;

    @BeforeAll
    void startMigrateAndCreateRestrictedRole() throws Exception {
        TestcontainersDockerPreflight.assumeDockerAvailable();
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("meeting_test").withUsername("meeting").withPassword("meeting_test");
        postgres.start();
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration").load().migrate();

        superDs = new SingleConnectionDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), true);
        try (Statement stmt = superDs.getConnection().createStatement()) {
            stmt.execute("CREATE ROLE meeting_app LOGIN PASSWORD 'meeting_app' NOSUPERUSER NOBYPASSRLS");
            stmt.execute("GRANT USAGE ON SCHEMA public TO meeting_app");
            stmt.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO meeting_app");
            stmt.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO meeting_app");
            // Seed reference rows as superuser (tenants RLS allows only self-row access).
            stmt.execute("INSERT INTO tenants (id, name) VALUES ('" + TENANT + "', 'RLS IT') ON CONFLICT DO NOTHING");
            stmt.execute("INSERT INTO meetings (id, tenant_id, title, status, language, transcript_version, minutes_version) "
                + "VALUES ('meeting_rls', '" + TENANT + "', 'RLS meeting', 'PROCESSING', 'zh', 0, 0)");
        }

        appDs = new SingleConnectionDataSource(postgres.getJdbcUrl(), "meeting_app", "meeting_app", true);
        JdbcTemplate appJdbc = new JdbcTemplate(appDs);
        repo = new JdbcProcessingTaskRepository(appJdbc);
        tenantTx = new TenantTransactionTemplate(
            new TransactionTemplate(new DataSourceTransactionManager(appDs)),
            new TenantSessionContext(appJdbc));
    }

    @AfterAll
    void stop() {
        if (appDs != null) appDs.destroy();
        if (superDs != null) superDs.destroy();
        if (postgres != null) postgres.stop();
    }

    @Test
    void rlsHidesRowsWithoutTenantContext() {
        seedWorkerDagDoneTask("task_rls_visibility");
        // Same restricted connection, no tenant context: RLS must return nothing.
        assertThat(repo.findById(TENANT, "task_rls_visibility")).isEmpty();
        // With tenant context: visible.
        assertThat(tenantTx.execute(TENANT, null, null, () -> repo.findById(TENANT, "task_rls_visibility"))).isPresent();
    }

    @Test
    void workerPhaseCompletedDrivesSummaryAndExtractionUnderNonSuperuserRls() {
        seedWorkerDagDoneTask("task_rls_llm");
        TaskStepProgressService progress = new TaskStepProgressService(repo, tenantTx);
        MinutesApplicationService minutes = mock(MinutesApplicationService.class);
        ExtractionApplicationService extraction = mock(ExtractionApplicationService.class);
        JavaLlmPhaseOrchestrator orchestrator = new JavaLlmPhaseOrchestrator(progress, repo, minutes, extraction, tenantTx);
        WorkerPhaseCompletedListener listener = new WorkerPhaseCompletedListener(progress, repo, orchestrator, null, tenantTx);

        listener.onWorkerPhaseCompleted(new WorkerPhaseCompletedEvent(
            "evt_rls", TENANT, "task_rls_llm", "MEETING_FULL_PIPELINE", 1,
            ProcessingTaskStatus.PARTIAL_SUCCEEDED, List.of(), List.of(), null, 0, NOW));

        ProcessingTask after = tenantTx.execute(TENANT, null, null, () -> repo.findById(TENANT, "task_rls_llm")).orElseThrow();
        // Pre-fix behavior: listener's unscoped read found zero rows, swallowed TASK_NOT_FOUND,
        // and the task stayed RUNNING/WORKER_DAG_DONE with SUMMARY still PENDING.
        assertThat(after.phase()).isEqualTo(ProcessingTaskPhase.TERMINAL);
        assertThat(after.step(ProcessingStep.SUMMARY).status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(after.step(ProcessingStep.EXTRACTION).status()).isEqualTo(StepStatus.SUCCEEDED);
    }

    private void seedWorkerDagDoneTask(String taskId) {
        ProcessingTask task = ProcessingTask.create(taskId, TENANT, "meeting_rls", "MEETING_FULL_PIPELINE",
            List.of(ProcessingStep.AUDIO_UPLOAD, ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR,
                ProcessingStep.ALIGNMENT, ProcessingStep.DIARIZATION, ProcessingStep.SPEAKER_EMBEDDING,
                ProcessingStep.SPEAKER_MATCHING, ProcessingStep.TRANSCRIPT_MERGE, ProcessingStep.RAG_INDEXING,
                ProcessingStep.SUMMARY, ProcessingStep.EXTRACTION), NOW);
        task.markJavaStepSucceeded(ProcessingStep.AUDIO_UPLOAD, NOW);
        task.enqueue(NOW);
        task.claimOrRenewLease(1, "worker_a:" + taskId + ":1", NOW);
        task.completeWorkerPhase(ProcessingTaskStatus.PARTIAL_SUCCEEDED,
            List.of(ProcessingStep.AUDIO_PREPROCESS, ProcessingStep.ASR, ProcessingStep.DIARIZATION,
                ProcessingStep.SPEAKER_EMBEDDING, ProcessingStep.SPEAKER_MATCHING, ProcessingStep.TRANSCRIPT_MERGE),
            List.of(new WorkerPhaseCompletedEvent.SkippedStep(ProcessingStep.ALIGNMENT, "worker skip"),
                new WorkerPhaseCompletedEvent.SkippedStep(ProcessingStep.RAG_INDEXING, "java-side chunks")),
            1, "worker_a:" + taskId + ":1", NOW);
        tenantTx.executeWithoutResult(TENANT, null, null, () -> repo.save(task));
    }
}
```

(Note: `SingleConnectionDataSource` serializes the two `tenantTx` transactions — fine for this IT. The mocked Minutes/Extraction services are concrete classes; Mockito handles non-final classes. The `WorkerPhaseCompletedListener` 5-arg + `JavaLlmPhaseOrchestrator` 5-arg constructors come from Task 3.)

- [ ] **Step 2: Run** — `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw verify -q` (Docker/Colima required; the class is named `*IT` so Failsafe picks it up). Expected: passes with the Task 3 fix; if you temporarily revert the listener's tenant-scoped read, `workerPhaseCompletedDrivesSummaryAndExtractionUnderNonSuperuserRls` fails with the task stuck at `WORKER_DAG_DONE` — that is the C3 regression demonstration.

- [ ] **Step 3: Commit** — `git add -A && git commit -m "fix(api): non-superuser RLS integration test for callback→LLM-phase flow (D11)"`

## Task 17: Final verification sweep

- [ ] Run the full gate locally:

```bash
cd packages/meeting-contracts && npm run check && npm run codegen && git diff --exit-code
cd ../../apps/meeting-api
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw test          # unit + ArchUnit
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw verify -q     # + ITs (Docker)
psql -v ON_ERROR_STOP=1 -f meeting-api-infrastructure/src/main/resources/db/migration/V202606121000__callback_nonces.sql  # against scratch pg15 (ddl-check parity)
psql -v ON_ERROR_STOP=1 -f meeting-api-infrastructure/src/main/resources/db/migration/V202606121010__api_idempotency_keys.sql
```

- [ ] Confirm ArchUnit still green (no app→infrastructure imports were added; `DashScopeLlmGateway` (infra) → `TenantScopedTransaction` (app) is allowed by rule 4).
- [ ] Cross-plan smoke once P1 lands: worker `/complete` with `PARTIAL_SUCCEEDED` + skipped `ALIGNMENT`/`RAG_INDEXING` → meeting reaches `SUCCEEDED`; heartbeat replay → 401; `/artifacts` then `/transcript` referencing `artifact_manifest_{taskId}_{attemptNo}` → both 200.

## Minor findings — triage table (no tasks)

| Location | Problem | One-line fix |
|---|---|---|
| `ProcessingTaskCallbackApplicationService.java:132` + creation paths | Lease TTL 5min vs spec 120s | Superseded by Task 5 (`ProcessingTask.LEASE_TTL` = 120s); listed for traceability |
| `ProcessingTaskCallbackController.fail:143-145` | NPE when `error` object missing → 500 instead of 422 | `if (!(payload.get("error") instanceof Map)) throw new IllegalArgumentException("error object is required");` before the cast |
| `SpeakerCandidatesCallbackApplicationService.java:140-147` | KMS `envelopeGateway.encrypt` result discarded, long external call inside TX | Drop the encrypt-and-discard block entirely (embedding is not persisted on this path); keep `zeroFloats` |
| `SpeakerCandidatesCallbackApplicationService.java:189` | `tenantId` read via `optionalString` while required elsewhere | Switch controller binding to `requiredString(payload, "tenantId")` |
| `MinutesApplicationService.java:274-276` | Phase-K remnant comment ("LlmGateway already fail-closes on CONFIDENTIAL/SECRET") | Delete the stale comment (gate removed 2026-06; do not re-add) |
| `BreakGlassAccessGuard.java:16` | Javadoc still references security-level gating | Reword javadoc to legal-hold/break-glass semantics only |
| `apps/meeting-api/SPEC.md` §8 item 6 | "CONFIDENTIAL / SECRET 会议文本" listed as LLM-forbidden — classification no longer exists | Delete item 6 from the §8 list |
| `ProcessingTask.completeWorkerPhase` | Doesn't require all worker steps terminal; a RUNNING worker step can ride into SUCCEEDED via `completeJavaPhase` (which checks Java steps only) | In `completeWorkerPhase`, after marking completed/skipped, throw `IllegalStateException` if any `AI_WORKER_CALLBACK` step is still PENDING/QUEUED/RUNNING |
| `ProcessingTask.confirmCancelled` | Marked SKIPPED steps CANCELLED | Fixed in Task 6; listed for traceability |
| `JdbcProcessingTaskRepository.java:194` | `retryable` restored as `last_error_code != null` (loses real flag) | Acceptable approximation for now; if needed, persist a `retryable boolean` column in a future migration — do not change DDL in this volume |
| `ProcessingTaskCallbackApplicationService.updateStep`/`fail` | Meeting-linkage check runs before idempotency lookup — replays can 409/422 instead of replaying | Reorder: `persistCallbackEvent` replay-check before `requireCallbackMeetingMatchesTask` (SPEC §6 order: idempotency step 5 precedes linkage step 8) — fold into Task 5's restructuring if touched, else leave for next pass |

## Execution order & dependencies

1 (contracts) → 2 (exceptions) → 3 (async/tenant TX/CAS/recovery) → 4 (de-TX) → 5 (lease claim) → 6 (requeue/cancel/retry + locks) → 7 (nonce/heartbeat) → 8 (expectedInputVersion, needs 6's archive) → 9 (/artifacts, needs 5's claim + 7's guard) → 10 (embeddings, needs 6+8) → 11 (meeting closure) → 12 (public idempotency) → 13 (RAG cache, needs 4's TX layout) → 14 (outbox fence) → 15 (auth refresh, independent) → 16 (RLS IT, needs 3+5) → 17 (verification).
