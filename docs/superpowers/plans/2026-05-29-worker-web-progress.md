# Worker-Web Speaker Upload Progress and P5 Acceptance Prep

**Date:** 2026-05-29
**Tracked baseline:** `8f51b54ad378b21ea39cc876837eacc9f1723110`
**Scope:** Integration, acceptance, and docs tracking for the 2026-05-27 worker-web new-person enrollment + one-shot meeting pipeline plan.

This file records repository evidence only. It does not claim manual P5 walkthrough completion.

---

## Progress Matrix

| Phase | Plan scope | Current status | Repository evidence | Acceptance impact |
|---|---|---:|---|---|
| P1 Contracts | `POST /api/persons`, generic `/api/files` multipart endpoints, person/file error codes, generated artifacts | Done | `packages/meeting-contracts/openapi/public-api.yaml` has `/files`, `/files/{uploadId}/parts`, `/files/{uploadId}/complete`, `/files/{uploadId}/abort`, and `/persons`; `packages/meeting-contracts/schemas/common/error-codes.yaml` has `PERSON_DISPLAY_NAME_REQUIRED`, `PERSON_DUPLICATE`, `FILE_UPLOAD_NOT_FOUND`, `FILE_MIME_NOT_ALLOWED`; generated Java public API models include `CreatePersonRequest`, `PersonResponse`, `FileUploadSessionResponse`, `FileUploadCompleteResponse`; `JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home npm run check` passed. | P1 no longer blocks P2/P3/P4. |
| P2 Java meeting-api | Person aggregate/controller/facade/repository; generic file upload controller/service/repository; `SpeakerAutoConfirmService`; `WorkerPhaseCompletedListener` auto-confirm before LLM; tests | Implemented in working tree | Implementation files now exist under `meeting-api-domain`, `meeting-api-client`, `meeting-api-app`, `meeting-api-infrastructure`, and `meeting-api-adapter`: `PersonController`, `PersonApplicationService`, `JdbcPersonRepository`, `FileUploadController`, `GenericFileUploadApplicationService`, `JdbcGenericFileUploadRepository`, `SpeakerAutoConfirmService`, and migrations `V202605270001__person_displayname_index.sql` / `V202605270002__generic_file_upload_sessions.sql`. `WorkerPhaseCompletedListener` calls auto-confirm before Java LLM. `MeetingSpeakerApplicationService` now resolves confirmed speaker display names through `PersonRepository` when no profile id is supplied, so auto-confirmed transcript/speaker DTOs use person display names instead of raw person ids. Focused Maven verification passed; full non-sandbox Java verification also passed with `JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw verify -q`. | No longer blocks P5 by implementation evidence. |
| P3 ai-worker BFF | `/admin/persons`, `/admin/files`, enrollment commit path/body fixes, audio upload passthrough, remove manual start/finalize orchestration, tests | Implemented in working tree | `apps/ai-worker/ai_worker/admin/persons.py` and `tests/admin/test_persons.py`; `apps/ai-worker/ai_worker/admin/files.py` and `tests/admin/test_files.py`; `apps/ai-worker/ai_worker/admin/router.py` mounts persons/files routers; `apps/ai-worker/ai_worker/admin/enrollment.py` posts `/api/speaker-profiles`, `/api/files`, then `/api/files/{uploadId}/parts`, then PUTs the signed URL, then posts Java's real `/api/files/{uploadId}/complete` path before `/api/speaker-profiles/{profileId}/enrollments`; `apps/ai-worker/ai_worker/admin/meetings.py` exposes document create/search passthrough, document attach, audio upload passthrough, and no longer exposes `:start-processing` or `:finalize`. `apps/ai-worker/ai_worker/interfaces/api/main.py` adds a narrow same-origin `/api/processing-tasks/{taskId}` + `/events` proxy for Python-hosted `/workstation/`. Fresh checks: `uv run pyright ai_worker/` = 0 errors; targeted enrollment test = `6 passed`; full ai-worker suite = `202 passed, 2 skipped`. | Ready for P5 integration once committed/merged. |
| P4 ai-worker-web frontend | Replace wizard with 3 pages; `PersonCreateModal`; `MultipartUploader`; `/meetings/new`; `/meetings/:id`; route cleanup; Vitest/Playwright updates | Implemented in working tree | `App.tsx` routes `/meetings/new` to `NewMeetingPage` and `/meetings/:meetingId` to `MeetingDetailPage`; wizard/workstation source and `wizard-happy-path.spec.ts` are deleted; new `MultipartUploader`, `PersonCreateModal`, `EnrollmentPage` modal flow, `NewMeetingPage`, `MeetingDetailPage`, unit tests, and Playwright specs are present. `MultipartUploader` honors the server-returned `partSizeBytes` so Java-coerced single-part uploads are not split client-side. `MeetingDetailPage` uses fetch-stream SSE so the in-memory Bearer token is sent on `/api/processing-tasks/{taskId}/events`, and it now renders Java `MeetingSpeakerDTO` fields (`speakerLabel`, `confirmationStatus`, `candidatePersonIds`) with legacy fallbacks. Fresh checks: `npm run type-check` passed; `npm test` = 12 files / 54 tests passed; `npm run build` passed; `npm run e2e` = 2 passed. | Ready for P5 integration once backend stack is running. |
| P5 Integration/docs/acceptance | Full docker-compose/manual walkthrough; screenshots; docs tracking; CI matrix; failure-path smokes | Prepared, not run | This file records the progress matrix, commands, URLs, screenshot targets, and blockers. `todo-final.md` has been updated with a tracking section. `deploy/DEPLOY.md` and `infra/meeting-infra/k8s/base/ai-worker/statefulset.yaml` now document/route workstation `/api/*` to `meeting-api`; local `http://localhost:8090/workstation/` is covered by the narrow Python task-read proxy. `apps/ai-worker-web/SPEC.md` is absent, so the SPEC.md changelog step is skipped for now. Docker stack and browser walkthrough were not started. | Remaining blocker is real full-stack/manual acceptance, not local implementation evidence. |

---

## P5 Acceptance Blockers

- P2/P3/P4 are implemented in the working tree and have focused verification evidence, but the changes are not committed yet.
- Manual acceptance has not been run. No P5 screenshots should be considered produced until the Docker/full-stack walkthrough is actually executed.

---

## Exact Verification Command Matrix

Run these from repo root unless a command includes `cd`.

### P1 Contracts

```bash
cd packages/meeting-contracts
JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home npm run check
JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home npm run codegen
git diff --exit-code
```

### P2 Java

```bash
cd apps/meeting-api
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test
./mvnw verify -q
JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home mvn -pl meeting-api-start -am -Dtest='PersonApplicationServiceTest,GenericFileUploadApplicationServiceTest,FileUploadControllerTest,SpeakerAutoConfirmServiceTest,WorkerPhaseCompletedListenerTest' -Dsurefire.failIfNoSpecifiedTests=false test -q
```

### P3 ai-worker

```bash
cd apps/ai-worker
uv run pyright ai_worker/
uv run pytest tests/ -x -q
```

Targeted P3 smoke used during this audit:

```bash
cd apps/ai-worker
uv run pytest tests/admin/test_persons.py tests/admin/test_files.py tests/admin/test_enrollment_session.py tests/admin/test_meeting_orchestration.py -q
```

### P4 ai-worker-web

```bash
cd apps/ai-worker-web
npx tsc --noEmit
npm test
npm run build
npm run e2e
```

### meeting-web Sanity

```bash
cd apps/meeting-web
npx tsc --noEmit
npm test
```

### P5 Docker/DDL

Do not start this stack until P2/P3/P4 are complete and merged into the integration branch.

```bash
cp .env.example .env  # only if .env is absent
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
docker ps
```

Optional full-stack profile once app images are ready:

```bash
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml --profile workstation up -d
curl -fsS http://localhost:8080/actuator/health/readiness
curl -fsS http://localhost:8090/internal/health
```

DDL drift check from the P5 plan:

```bash
docker run --rm -e POSTGRES_PASSWORD=test -p 55432:5432 -d --name pg-final pgvector/pgvector:pg15
sleep 4
for sql in apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V*.sql; do
  psql -v ON_ERROR_STOP=1 postgresql://postgres:test@localhost:55432/postgres -f "$sql"
done
docker rm -f pg-final
```

---

## Manual Acceptance Checklist

Use `http://localhost:5174/workstation/` for Vite dev when running the SPA directly, or `http://localhost:8090/workstation/` when using the ai-worker static admin UI from compose.

### Happy Path 1: New-Person Speaker Enrollment

Prerequisites:

```bash
cp .env.example .env  # if absent
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
cd apps/meeting-api && ./mvnw -pl meeting-api-start -am install -DskipTests
java -jar meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar &
curl -fsS http://localhost:8080/actuator/health/readiness
cd ../ai-worker && AI_WORKER_ENABLE_ADMIN=true uv run ai-worker-api &
curl -fsS http://localhost:8090/internal/health || curl -fsS http://localhost:8090/admin/healthz || curl -fsS http://localhost:8090/healthz
cd ../ai-worker-web && npm run dev
```

Walkthrough:

- Open `http://localhost:5174/workstation/enrollment` (or `http://localhost:8090/workstation/enrollment`).
- Login as tenant admin.
- Search `李四` and confirm no usable existing result.
- Click `+ 新建人员`; submit `displayName=李四`; confirm the modal closes and the selected person is `李四`.
- Click `创建录入会话`; verify a session id appears.
- Drop a 5-second WAV; click `上传并预览`; verify `quality_score >= 0.5`.
- Click `确认录入`; verify state is `COMMITTED`.

DB evidence:

```bash
psql "$POSTGRES_URL" -c "SELECT id, display_name FROM persons WHERE display_name='李四' ORDER BY created_at DESC LIMIT 1;"
psql "$POSTGRES_URL" -c "SELECT id, person_id FROM speaker_profiles WHERE person_id=(SELECT id FROM persons WHERE display_name='李四' ORDER BY created_at DESC LIMIT 1);"
psql "$POSTGRES_URL" -c "SELECT id, speaker_profile_id FROM speaker_enrollments ORDER BY created_at DESC LIMIT 1;"
```

Screenshot target:

```text
docs/superpowers/specs/screenshots/2026-05-27-enrollment-new-person.png
```

### Happy Path 2: New Meeting One-Shot Pipeline

Walkthrough:

- Open `http://localhost:5174/workstation/meetings/new` (or `http://localhost:8090/workstation/meetings/new`).
- Fill `title=季度评审`; set security `INTERNAL`; add terms `LLM` and `DAG`.
- Drop a sample PDF; wait for upload progress to complete and confirm the UI shows one uploaded new document.
- Drop an MP3 of 50 MB or less.
- Click `开始处理`; verify URL navigates to `/meetings/<new-id>`.
- Watch the SSE step grid: `AUDIO_PREPROCESS`, `ASR`, `ALIGNMENT`, `DIARIZATION`, `SPEAKER_EMBEDDING`, `SPEAKER_MATCHING`, `TRANSCRIPT_MERGE`, `RAG_INDEXING`, `SUMMARY`, `EXTRACTION`.
- Verify `SUMMARY` and `EXTRACTION` succeed and minutes markdown is rendered.
- Verify speakers show confirmed names with `自动认定` where confidence is `>= 0.85`, otherwise retained `SPEAKER_xx` labels.
- Click `创建导出`; wait for `SUCCEEDED`; download the `.docx` and open it in Word/LibreOffice.

Log evidence:

```bash
docker logs meeting-api 2>&1 | grep auto_confirm | head
```

Screenshot targets:

```text
docs/superpowers/specs/screenshots/2026-05-27-new-meeting-pipeline.png
docs/superpowers/specs/screenshots/2026-05-27-new-meeting-result.png
```

---

## Failure-Path Smokes

### Duplicate Person Name

- Open `/enrollment`.
- Create `李四` once.
- Attempt to create another `李四` without `forceCreate`.
- Expected: modal shows upstream `PERSON_DUPLICATE`, a duplicate list, `使用已有`, and `仍创建新的`.
- Screenshot target:

```text
docs/superpowers/specs/screenshots/2026-05-27-duplicate-person.png
```

### Disallowed Reference File MIME

- Open `/meetings/new`.
- Drag a `.exe` into the reference document upload area.
- Expected: upstream `FILE_MIME_NOT_ALLOWED` is shown; the file is not added to pending/attached docs.
- Screenshot target:

```text
docs/superpowers/specs/screenshots/2026-05-27-file-mime-not-allowed.png
```

### CONFIDENTIAL Fail-Closed Meeting

- Open `/meetings/new`.
- Repeat the new-meeting flow with security `CONFIDENTIAL`.
- Expected: pipeline stops or partial-succeeds at LLM phase with `SECURITY_LEVEL_BLOCKED`; UI shows a warning banner; docx export remains possible from available content.
- Screenshot target:

```text
docs/superpowers/specs/screenshots/2026-05-27-confidential-blocked.png
```

---

## Verification Run on 2026-05-29

Passed:

```bash
cd packages/meeting-contracts && JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home npm run check
git diff --check
cd apps/ai-worker && uv run pyright ai_worker/
cd apps/ai-worker && uv run pytest tests/admin/test_persons.py tests/admin/test_files.py tests/admin/test_enrollment_session.py tests/admin/test_meeting_orchestration.py -q
cd apps/ai-worker && uv run pytest tests/test_workstation_mount.py tests/admin/test_persons.py tests/admin/test_files.py tests/admin/test_enrollment_session.py tests/admin/test_meeting_orchestration.py -q
cd apps/ai-worker && uv run pytest tests/ -x -q
cd apps/ai-worker-web && npx tsc --noEmit
cd apps/meeting-api && JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home mvn -pl meeting-api-start -am -Dtest='PersonApplicationServiceTest,GenericFileUploadApplicationServiceTest,FileUploadControllerTest,SpeakerAutoConfirmServiceTest,WorkerPhaseCompletedListenerTest' -Dsurefire.failIfNoSpecifiedTests=false test -q
cd apps/ai-worker-web && npm test
cd apps/ai-worker-web && npm run build
cd apps/ai-worker-web && npm run e2e
cd apps/meeting-web && npx tsc --noEmit
cd apps/meeting-web && npm test
cd apps/meeting-api && JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test
cd apps/meeting-api && JAVA_HOME=/Users/friedhelmliu/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw verify -q
```

Observed results:

- Contracts check completed successfully with generated artifacts in sync when `JAVA_HOME` was pinned to JDK 17.
- ai-worker pyright returned `0 errors, 0 warnings, 0 informations`.
- Targeted ai-worker admin/workstation tests passed: `34 passed`.
- Full ai-worker tests passed: `202 passed, 2 skipped`.
- meeting-api focused P2/P3 listener tests passed with the reactor `-am` Maven command above.
- meeting-api full unit suite passed outside the sandbox with JDK 17 + Mockito attach enabled: `516` tests, `0` failures/errors.
- meeting-api full `verify -q` passed outside the sandbox; Testcontainers RabbitMQ/Postgres and Flyway migrations completed through `v202605270002`.
- ai-worker-web typecheck passed against the new page implementation.
- git whitespace check passed.
- ai-worker-web unit tests passed: 12 files, 54 tests.
- ai-worker-web build passed; largest gzip chunks remained below the 200 KB budget.
- ai-worker-web Playwright specs passed: 2 tests.
- meeting-web typecheck passed after removing an unused helper; meeting-web unit tests passed: 27 files, 164 tests.

Not run:

- Docker-compose/manual walkthrough and screenshot capture.
