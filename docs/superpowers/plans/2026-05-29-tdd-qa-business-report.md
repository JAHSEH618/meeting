# TDD/QA Business Correctness Report

Date: 2026-05-29
Scope: current dirty worktree under `/Users/friedhelmliu/CodeSpace/meeting`
Method: four `gpt-5.5`/`xhigh` subagents plus coordinator verification. The report records current evidence only.

## 1. Engineering Analysis And Business Understanding

### Project Structure

- `packages/meeting-contracts`: OpenAPI, JSON Schema, enums, and error codes. This is the cross-workspace contract source of truth.
- `apps/meeting-api`: Java 17/Spring Boot/COLA multi-module business source of truth. It owns DB writes, permissions, task state, LLM phase, outbox routing, Flyway migrations, and internal callback validation.
- `apps/ai-worker`: Python 3.11/FastAPI BFF plus worker runtime. For this scope it hosts `/admin/*` workstation endpoints and proxies to Java; it does not own business persistence.
- `apps/ai-worker-web`: React/Vite operator workstation. It now uses `/enrollment`, `/meetings/new`, and `/meetings/:id`; the old wizard route was removed.
- `apps/meeting-web`: end-user SPA. It was sanity checked because the broader CI matrix includes it.

### Core Business Rules Used As Test Oracles

- Java remains the sole business writer and permission authority. BFF/frontend logic may orchestrate calls but must not decide persistence or authorization.
- Person creation uses same-tenant exact `displayName` soft dedup. Duplicate names return `PERSON_DUPLICATE` with matches unless `forceCreate=true`; no DB unique constraint should block legitimate same-name people.
- Generic `/api/files` upload is tenant scoped, MIME allowlisted, and creates durable `MeetingFile` records. It is currently single-put but must return a usable signed upload part in the init response because the contract exposes `FileUploadSession.parts`.
- Enrollment commit stores audio locally only until commit, then creates a Java speaker profile, uploads audio through generic file upload, creates the Java enrollment with the completed `audioFileId`, and deletes the temp audio.
- New meeting one-shot flow creates a meeting, applies glossary, attaches reference documents, uploads meeting audio, and lets Java create/dispatch the processing task after audio complete. Manual start/finalize endpoints are no longer part of this path.
- `WORKER_PHASE_COMPLETED` for `MEETING_FULL_PIPELINE` must auto-confirm speakers before Java LLM phase when the task is not held. Auto-confirm failures must not block SUMMARY/EXTRACTION.
- Speaker auto-confirm threshold is `>= 0.85`; labels below threshold remain `SPEAKER_xx`.
- Human speaker confirmation must enforce `expectedTranscriptVersion` before mutating speaker records, transcript segments, and RAG stale state.
- `CONFIDENTIAL`/`SECRET` LLM processing must fail closed with `SECURITY_LEVEL_BLOCKED`.
- Outbox publishing must only route known worker/export events; in-process domain events are skipped and unknown events become unroutable/DLQ.

### Key Paths And State Changes

- Person flow: `ai-worker-web PersonCreateModal` -> `POST /admin/persons` -> `POST /api/persons` -> `persons` table + outbox event.
- Enrollment flow: temp session/audio in `ai-worker` -> preview -> Java profile -> generic file upload init/part/complete -> Java enrollment -> temp file cleanup.
- Meeting flow: `NewMeetingPage` form -> document upload/register/attach -> audio upload complete -> Java `ProcessingTask`/MQ -> worker callbacks -> `WorkerPhaseCompletedListener` -> auto-confirm -> Java LLM -> terminal state.
- Task/status flow: frontend reads meeting/task and subscribes to task SSE; hosted workstation uses narrow same-origin `/api/processing-tasks/{taskId}` proxy in `ai-worker`.
- Speaker confirmation flow: speaker candidate or human input -> `MeetingSpeakerApplicationService.confirm` -> meeting speaker confirmation -> transcript segment speaker update -> RAG chunk stale marking.

## 2. Test Case Inventory

### Contracts

- Positive: `/persons`, `/files`, generated Java/TS/Python codegen surfaces and fixtures remain valid.
  Expected: spectral/schema/fixtures/codegen drift all pass.
- Negative: invalid processing task fixtures and error envelopes are rejected.
  Expected: contract check rejects invalid fixtures while valid fixtures pass.
- Business rule: error codes include `PERSON_DISPLAY_NAME_REQUIRED`, `PERSON_DUPLICATE`, `FILE_UPLOAD_NOT_FOUND`, `FILE_MIME_NOT_ALLOWED`.
  Expected: error-code consistency passes.

### Java meeting-api

- Positive: create person with valid display name.
  Expected: person DTO is returned with trimmed display name and new id.
- Negative: blank display name.
  Expected: `PERSON_DISPLAY_NAME_REQUIRED`.
- Business rule: same-tenant duplicate person without `forceCreate`.
  Expected: `PERSON_DUPLICATE` and matching existing persons are exposed.
- Business rule: duplicate person with `forceCreate=true`.
  Expected: a distinct person is created; no unique DB rejection.
- Positive: generic file upload allows reference docs and supported audio MIME used by enrollment commit.
  Expected: session is created and content type is preserved.
- Negative: disallowed MIME such as `.exe`.
  Expected: `FILE_MIME_NOT_ALLOWED`, HTTP 415.
- Business rule: generic upload init returns initial part metadata.
  Expected: `FileUploadSession.parts` has part 1 with signed URL and request headers.
- Business rule: generic upload object path isolation.
  Expected: object key starts with `tenants/{tenantId}/generic-files/{uploadId}/`.
- Business rule: single-put part-size coercion.
  Expected: effective part size covers the whole file and part 2 is rejected.
- Positive: complete generic upload.
  Expected: durable `MeetingFile` has `meetingId=null`, `fileType=GENERIC`, `filePurpose=REFERENCE`.
- Negative: abort generic upload.
  Expected: upload state becomes `ABORTED` and temporary object deletion is requested.
- Business rule: speaker auto-confirm above threshold.
  Expected: label is confirmed to top candidate and transcript display name resolves through `Person`.
- Negative/business rule: low confidence or multiple/ambiguous candidates.
  Expected: no auto-confirm; label remains unconfirmed.
- Business rule: auto-confirm failure isolation.
  Expected: failed label does not block other labels or Java LLM phase.
- Business rule: human confirm stale transcript version.
  Expected: version conflict before speaker/transcript/RAG mutations.
- Business rule: `WORKER_PHASE_COMPLETED` routing.
  Expected: held tasks wait, full meeting tasks start Java LLM, non-LLM worker tasks terminalize directly, listener swallows unexpected failures.
- Business rule: outbox routing.
  Expected: worker/export events route; internal-only events skip; unknown/invalid events are DLQ/unroutable.

### Python ai-worker

- Positive: admin persons router.
  Expected: JWT-gated thin passthrough to Java and upstream response is preserved.
- Negative: person duplicate passthrough.
  Expected: upstream `PERSON_DUPLICATE` status/body is returned unchanged.
- Positive: admin files router.
  Expected: init, part, complete, and abort map to `/api/files` Java endpoints.
- Negative: MIME not allowed passthrough.
  Expected: upstream `FILE_MIME_NOT_ALLOWED` is preserved.
- Positive: enrollment commit.
  Expected: Java calls happen in order: `/api/speaker-profiles`, `/api/files`, `/api/files/{id}/parts`, signed PUT, `/api/files/{id}/complete`, `/api/speaker-profiles/{profileId}/enrollments`; temp audio is removed.
- Negative: enrollment missing preview/audio/session.
  Expected: stable 404/409 admin error envelopes.
- Business rule: enrollment payload contract.
  Expected: profile uses `consentReference`; enrollment uses `audioFileId` and `consentReference`.
- Business rule: meeting orchestration cleanup.
  Expected: no manual start/finalize orchestration is needed after audio complete.
- Business rule: hosted workstation task proxy.
  Expected: `/api/processing-tasks/{taskId}` and `/events` are narrow read-only proxies for the same-origin SPA.

### ai-worker-web

- Positive: create person from enrollment modal.
  Expected: created person is selected and used for session creation.
- Negative/business rule: duplicate person.
  Expected: duplicate matches are displayed, user can select existing person or explicitly force-create.
- Bug regression: editing email after duplicate state.
  Expected: stale duplicate resolution clears and normal create can be retried without `forceCreate`.
- Positive: multipart upload happy path.
  Expected: init -> signed PUT -> complete, progress updates, etags are used.
- Negative: part failure and cancel.
  Expected: retry policy is applied; permanent failure/cancel aborts upload.
- Business rule: server-returned part size.
  Expected: uploader respects Java-coerced single-part sessions.
- Positive: new meeting one-shot.
  Expected: requires title/audio, uploads docs immediately, creates meeting, updates glossary, attaches refs, uploads audio, and navigates to detail.
- Negative: disallowed reference file MIME.
  Expected: upload error is shown and file is not added as pending/attached.
- Positive: meeting detail.
  Expected: task steps, speakers, sanitized markdown minutes, partial/security warnings, export polling, and SSE/fallback behavior render correctly.
- Business rule: route cleanup.
  Expected: old wizard route/files/spec are removed; new routes are `/enrollment`, `/meetings/new`, `/meetings/:meetingId`.

### meeting-web Sanity

- Type gate: no unused declarations under strict TS settings.
  Expected: `npx tsc --noEmit` passes.
- Unit suite: existing end-user UI behavior remains passing.
  Expected: Vitest suite passes.

## 3. Test Execution Results

### Initial/Failure Evidence

- Java focused test first failed under default shell because Maven used JDK 26; this was an environment/toolchain mismatch, not a business failure. Re-run with JDK 17 passed.
- Java full `test` first failed in sandbox with Mockito inline self-attach and local `HttpServer` socket errors. Re-run outside sandbox with `JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true` passed.
- `meeting-web` typecheck initially failed on unused `formatTime` in `AudioUploadPage.tsx`. This was a real compile-gate issue, fixed by removing the unused helper.
- TDD red checks from subagents:
  - Generic upload init returned zero parts; regression failed with expected size 1 but was 0.
  - Speaker confirm had no `expectedTranscriptVersion` enforcement path; stale-version regression failed before implementation.
  - ai-worker enrollment commit test failed against contract-correct `consentReference`/`audioFileId` expectations.
  - PersonCreateModal duplicate retry test failed because `onCreated` was never called after editing only email.

### Final Verification Evidence

- `packages/meeting-contracts`: `JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home npm run check` -> passed; generated files in sync.
- `apps/meeting-api`: focused suite for person/file/upload/autoconfirm/listener/outbox/ArchUnit -> passed.
- `apps/meeting-api`: `JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test` outside sandbox -> `516` tests, `0` failures/errors.
- `apps/meeting-api`: `JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw verify -q` outside sandbox -> exit 0; Testcontainers RabbitMQ/Postgres and Flyway migrations ran, with schema at `v202605270002`.
- `apps/ai-worker`: `uv run pyright ai_worker/` -> `0 errors, 0 warnings, 0 informations`.
- `apps/ai-worker`: `uv run pytest tests/ -x -q` -> `202 passed, 2 skipped`.
- `apps/ai-worker-web`: `npx tsc --noEmit` -> passed.
- `apps/ai-worker-web`: `npm test` -> `12` files, `54` tests passed.
- `apps/ai-worker-web`: `npm run build` -> passed; gzip chunks below 200 KB budget.
- `apps/ai-worker-web`: `npm run e2e` -> `2 passed`.
- `apps/meeting-web`: `npx tsc --noEmit` -> passed after unused-helper fix.
- `apps/meeting-web`: `npm test` -> `27` files, `164` tests passed.
- Repo: `git diff --check` -> passed.

## 4. Bug List And Root Cause Classification

1. Generic file upload init did not return a usable initial upload part.
   - Symptom: real service returned an upload session with empty `parts`, while frontend/BFF contract expects a signed part in session data.
   - Root cause: controller-level mock coverage hid a service/contract mismatch.
   - Fix: persist initial part 1 and return presigned part metadata from `GenericFileUploadApplicationService.createSession`.
   - Business rule: upload init must be immediately usable by clients and match `FileUploadSession.parts`.

2. Human speaker confirmation ignored `expectedTranscriptVersion`.
   - Symptom: public controller accepted expected version but service mutation path did not enforce it.
   - Root cause: missing application-service guard before speaker/transcript/RAG mutations.
   - Fix: pass `expectedTranscriptVersion` from controller and reject stale versions before mutation.
   - Business rule: concurrent transcript edits must not be silently overwritten by stale speaker confirmation.

3. Enrollment commit used stale Java payload fields.
   - Symptom: BFF sent `consentSource`/`consentVersion` and `sourceAudioFileId`, which no longer match OpenAPI.
   - Root cause: test encoded an older payload shape instead of contract-correct fields.
   - Fix: send `consentReference: "workstation:v1"` for profile/enrollment and `audioFileId` for enrollment.
   - Business rule: BFF is a thin contract adapter; durable enrollment must be written by Java using current public API schema.

4. Person duplicate modal stayed stuck after editing only email.
   - Symptom: after duplicate response, changing email did not clear duplicate state; normal create stayed disabled.
   - Root cause: duplicate state was cleared on display-name edits but not email edits.
   - Fix: clear duplicate matches when email changes too.
   - Business rule: duplicate resolution should be tied to current submitted input; user must be able to correct fields and retry normal creation.

5. meeting-web type gate failed on unused helper.
   - Symptom: `npx tsc --noEmit` failed with `TS6133` for unused `formatTime`.
   - Root cause: dead helper remained after UI changes.
   - Fix: remove the unused helper.
   - Business rule: no direct business behavior changed; this keeps CI type gate reliable.

## 5. Remaining Questions And Limits

- Public mutating idempotency for new `POST /api/persons` and generic `/api/files` is not proven as replay-safe. They accept idempotency keys, but current evidence does not show persisted key/body-hash replay like internal callbacks.
- `consentReference="workstation:v1"` is inferred from prior `consentSource=workstation` and `consentVersion=v1`; confirm if a canonical consent reference format exists.
- Generic `/api/files` now allows audio MIME types for enrollment audio. This follows the P3 plan, but the original public endpoint description still says meeting audio should use meeting-scoped audio endpoints. Confirm whether enrollment/reference-audio use of generic files is the intended long-term contract.
- Auto-confirm ambiguity rule is currently conservative: only sufficiently confident candidate cases are auto-confirmed; confirm whether top1/top2 gap or exactly-one-candidate semantics should be formalized.
- Full manual P5 browser walkthrough and screenshot capture were not performed in this pass. Automated Playwright covers mocked happy paths, and `mvn verify` covers backend integration, but real operator walkthrough with DB evidence remains a separate acceptance step.
