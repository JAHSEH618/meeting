# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

Polyglot monorepo for a local meeting-intelligence system. Four cooperating workspaces:

| Path | Stack | Role |
|---|---|---|
| `apps/meeting-api/` | Java 17 · Spring Boot 3.3 · COLA-V5 multi-module Maven | Public API, SSE, internal callback receiver, business source of truth, task orchestrator |
| `apps/ai-worker/` | Python 3.11 · FastAPI · uv | GPU AI pipeline (ASR / diarization / speaker / embedding / rerank) |
| `apps/meeting-web/` | Node 20 · React 18 · Vite · TypeScript strict | SPA frontend, consumes only Public API + SSE |
| `packages/meeting-contracts/` | OpenAPI + JSON Schema + enums/error-codes YAML | Cross-workspace contract single source of truth |
| `infra/meeting-infra/` | Docker Compose, K8s, Terraform | Local + deployment definitions, observability dashboards |

Each workspace has its own `SPEC.md` — read it before non-trivial work in that workspace. `docs/spec.md` is the executable phase-1 spec; `docs/spec-fixes.md` and `docs/spec-clarifications.md` are errata that supersede `spec.md` on conflict. `docs/app-api-contracts.md` covers cross-app API/MQ/callback contracts; `docs/structure.md` is the detailed logical view.

## Commands

### Contracts (run first when changing any schema/enum/error code)

```bash
cd packages/meeting-contracts
npm install                          # one-time
npm run check                        # spectral lint + JSON Schema + enum consistency + fixtures (CI gate)
npm run lint:openapi                 # spectral only
npm run codegen                      # regen TS/Python/Java types — git diff must be clean afterwards
npm run codegen:check-temp           # zero-side-effect drift check: generate to a temp dir and diff
```

Individual codegen targets exist (`codegen:ts`, `codegen:py-callback`, `codegen:py-worker-internal`, `codegen:py-task-msg`, `codegen:java-public`, `codegen:java-worker-internal`, `codegen:java-export-job`). Java codegen requires JDK 17 (openapi-generator-cli respects `JAVA_HOME`).

### Java (`apps/meeting-api`)

Six Maven modules — path shorthand throughout this file refers to these:

| Module | Role |
|---|---|
| `meeting-api-start` | Spring Boot entry, config, health, component scan |
| `meeting-api-adapter` | REST / SSE / internal-callback controllers, `export-queue` consumer — protocol translation only |
| `meeting-api-app` | Use cases, transactions, tenant context, permission orchestration, outbox publish |
| `meeting-api-domain` | Aggregates, events, ports — no Spring/JDBC/MQ dependencies |
| `meeting-api-infrastructure` | Repositories, MQ publisher, KMS, TOS, LibreOffice, DashScope; owns Flyway migrations |
| `meeting-api-client` | DTOs / Commands / Queries / Facades / enums / error codes shared via codegen |

> **JDK 版本**：Maven Enforcer 要求 `[17,18)`。若本机默认 JDK 高于 17，请先显式设置 `JAVA_HOME`（例如 macOS：`export JAVA_HOME=$(/usr/libexec/java_home -v 17)`）。

```bash
./mvnw test                                            # unit + ArchUnit only — no Docker required
./mvnw verify -q                                       # full build + integration tests (CI command, requires Docker)
./mvnw -pl meeting-api-start -am compile               # fast compile loop
./mvnw -pl meeting-api-start -am install -DskipTests   # package without tests
./mvnw -pl meeting-api-domain test -Dtest=ClassName    # single unit test
java -jar meeting-api-start/target/meeting-api-start-0.1.0-SNAPSHOT.jar   # runs on :8080
```

ArchUnit boundary test lives at `meeting-api-start/src/test/java/com/meeting/api/ArchitectureBoundaryTest.java` and is an `ERROR`-level gate — don't skip it. Testcontainers baselines (`*IT.java`) run via Failsafe during `verify` and require a Docker daemon. Flyway migrations under `meeting-api-infrastructure/src/main/resources/db/migration/V{yyyyMMddHHmm}__*.sql` are the runtime schema source of truth; `docs/ddls/` is a review snapshot only.

### Python (`apps/ai-worker`)

```bash
uv sync --extra dev                              # install
uv run pytest tests/                             # all tests
uv run pytest tests/test_rerank.py::test_name    # single test
uv run pyright ai_worker/                        # type check (CI gate)
uv run ai-worker-api                             # runs FastAPI on :8090
```

Pyright excludes `ai_worker/generated/` (codegen output). Tests live in `tests/`.

### Frontend (`apps/meeting-web`)

```bash
npm install
npm run dev                                      # Vite dev server on :5173, proxies /api -> :8080
npm test                                         # vitest run (CI command)
npm run test:watch
npx vitest run src/path/to/test                  # single test
npx tsc --noEmit                                 # type check (CI gate)
npm run build                                    # tsc -b && vite build
npm run lint                                     # eslint src/ --ext .ts,.tsx
npm run codegen                                  # regen src/shared/api/types.gen.ts from contracts
npm run e2e:install                              # one-time: install Playwright chromium
npm run e2e                                      # Playwright E2E (e2e/playwright.config.ts) — excluded from vitest discovery
```

### Local infrastructure

```bash
cp .env.example .env
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml up -d
```

Brings up PostgreSQL+pgvector, RabbitMQ, MinIO (TOS replacement), and Vault-dev (KMS replacement). Add `--profile observability` to also start Prometheus + Grafana (Loki is TBD).

Per-app images live under each app (`apps/meeting-web/Dockerfile`, `apps/ai-worker/Dockerfile`); each has its own `.dockerignore`. K8s manifests are kustomize-style: `infra/meeting-infra/k8s/base/` + `overlays/{dev,prod}/`. `infra/meeting-infra/terraform/main.tf` is a stub for future cloud provisioning — not wired into CI.

### CI (`.github/workflows/ci.yml`)

Five jobs run in parallel on every PR — all must pass:

1. **contracts** — `npm run check` (Spectral lint + JSON Schema validation + enum / `pipelineSteps` consistency).
2. **meeting-api** — `./mvnw verify -q` (unit + ArchUnit + Testcontainers IT).
3. **ai-worker** — `uv run pyright ai_worker/` + `uv run pytest tests/ -x -q` + import smoke.
4. **meeting-web** — `npx tsc --noEmit` + `npm test`.
5. **ddl-check** — applies every `meeting-api-infrastructure/.../db/migration/V*.sql` to a fresh `pgvector/pgvector:pg15` with `psql -v ON_ERROR_STOP=1`.

The `ddl-check` job is a **hidden gate**: a syntax error in a new Flyway migration fails CI even when no Java test touches the new table. Run `psql -v ON_ERROR_STOP=1 -f V…__*.sql` against a throwaway PG locally before pushing migrations.

## Architecture

### Java owns business, Python owns compute

`meeting-api` is the only writer of business state, the only permission authority, and the only LLM caller. `ai-worker` consumes RabbitMQ tasks, runs the GPU pipeline, and writes results back through HMAC-signed internal callbacks — it never touches the business DB, never decides permissions, and never holds KMS credentials.

Three integration channels between them:

1. **Java → ai-worker (async work)**: RabbitMQ task message conforming to `packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json`. Per-resource-type queues: `audio-cpu-queue`, `gpu-asr-queue`, `gpu-diar-queue`, `gpu-speaker-queue`, `embed-queue`, `llm-queue`. `export-queue` is consumed by Java itself, not by ai-worker.
2. **ai-worker → Java (callbacks)**: HMAC-signed `PATCH /internal/processing-tasks/{taskId}/steps/{stepName}` plus artifact/transcript/speaker-candidates/embeddings/complete/fail endpoints — see `internal-callback-api.yaml`. Java validates HMAC, timestamp skew (±5min), nonce, idempotency, attempt, lease, tenant/task linkage.
3. **Java → ai-worker (sync RAG rerank)**: `POST /internal/rerank` with `ai-worker-internal-api.yaml` contract. Used inline during `POST /api/rag/query`.

**Two distinct HMAC secrets — never reuse**:
- `meeting.callback.hmac-secret` / `AI_WORKER_CALLBACK_HMAC_SECRET`: ai-worker → Java callbacks.
- `meeting.ai-worker.hmac-secret` / `AI_WORKER_INTERNAL_API_HMAC_SECRET`: Java → ai-worker rerank.

The HMAC `signing_string`'s `URL_PATH_WITH_QUERY` must include the `/internal` server prefix; servlet-relative paths break signature verification.

### COLA-V5 dependency direction (enforced by ArchUnit)

```
adapter  →  app, client
app      →  domain, client
infra    →  domain, client
start    →  adapter, app, infrastructure
domain   →  client only (no Spring Web / JDBC / MyBatis / RabbitMQ / TOS / DashScope)
```

`adapter` does protocol translation only — no DB access, no business decisions. `app` owns transactions, tenant context, permission orchestration, outbox writes, idempotency. `domain` is pure aggregates/events/ports. `infrastructure` implements ports (Repositories, Gateways, MQ publisher, KMS, TOS, LibreOffice, DashScope client). Business domains (`meeting`, `task`, `speaker`, `rag`, `document`, `export`, `compliance`, `audit`, etc.) are package boundaries inside each module, not separate services.

### Pipeline ownership

ProcessingTask has two orthogonal dimensions:

- **`status`**: `PENDING` → `QUEUED` → `RUNNING` → terminal (`SUCCEEDED` / `PARTIAL_SUCCEEDED` / `FAILED` / `CANCELLED`), with `ORPHANED` for lease expiry and `CANCEL_PENDING` while stopping.
- **`phase`**: `WORKER_DAG_RUNNING` → `WORKER_DAG_DONE` → `JAVA_LLM_RUNNING` → `TERMINAL`.

`processingStep` values are partitioned by owner:

| Step | Owner |
|---|---|
| `AUDIO_UPLOAD` | Java (marked `SUCCEEDED` at task creation) |
| `AUDIO_PREPROCESS`, `ASR`, `ALIGNMENT`, `DIARIZATION`, `SPEAKER_EMBEDDING`, `SPEAKER_MATCHING`, `TRANSCRIPT_MERGE`, `RAG_INDEXING` | ai-worker (`source=AI_WORKER_CALLBACK`) |
| `SUMMARY`, `EXTRACTION` | Java `TaskStepProgressService` in `meeting-api-app/.../app/task/` (`source=JAVA_TASK_SERVICE`) |
| `EXPORT` | Java `export-queue` consumer |

RabbitMQ task message's `pipelineSteps` **must not contain** `AUDIO_UPLOAD` / `SUMMARY` / `EXTRACTION` / `EXPORT`. The schema enforces this; ai-worker fail-fast rejects violators with `INVALID_TASK_MESSAGE`. Worker's `POST /internal/.../complete` carries `phase=WORKER_DAG` and only signals worker-DAG completion — it never directly drives the task to `SUCCEEDED`. Java's `WORKER_PHASE_COMPLETED` outbox listener then drives `SUMMARY` / `EXTRACTION` and finally `phase=TERMINAL`.

### Invariants that span files

These are not discoverable from a single file — keep them in mind:

1. **AI output ≠ business fact.** AI-generated action items / decisions / risks default to suggestions. User confirmation promotes them to facts. Regeneration produces diffs or new suggestions; user-confirmed fields are never silently overwritten.
2. **Security level gates LLM egress.** `PUBLIC` / `INTERNAL` may call DashScope (no redaction in phase 1). `CONFIDENTIAL` / `SECRET` automatic LLM is fail-closed → `SECURITY_LEVEL_BLOCKED`. Audio, normalized audio, speaker reference audio, speaker embeddings, and raw speaker-model output **never** leave the system to a third-party LLM.
3. **Speaker embeddings are KMS-envelope encrypted.** ai-worker callbacks carry plaintext `embedding.values` over internal-TLS+HMAC; Java envelope-encrypts via KMS before persisting. Plaintext is never written to TOS, never logged, never returned in any Public DTO. After callback success or retry exhaustion, ai-worker clears in-process plaintext references.
4. **RAG permissions computed live by Java.** pgvector is a candidate retriever only — never the permission authority. Retrieved chunks are filtered by a second-pass PostgreSQL permission check before reranking. Only `status=ACTIVE AND stale_status=ACTIVE` chunks reach the rerank gateway.
5. **STALE is separate from business status.** Editing a transcript marks downstream minutes / action items / decisions / risks / RAG chunks `STALE`. UI surfaces this; cache keys include the appropriate version.
6. **Outbox pattern.** Domain events go into `domain_events_outbox` in the same transaction as business writes. Per-aggregate `sequence_no` is monotonic (acquired via `SELECT ... FOR UPDATE` on latest outbox row of `(tenant_id, aggregate_type, aggregate_id)`). Publisher uses `SELECT ... FOR UPDATE SKIP LOCKED` to drain.
7. **Heartbeat callback exception.** `PATCH .../steps/{stepName}` with `status=RUNNING && progress>0` is a heartbeat: latest-wins update of `heartbeatAt`/`progress`/`leaseExpiresAt`, no `callback_events` row, no body-hash idempotency conflict. First `RUNNING(progress=0)`, `SUCCEEDED`, `FAILED` still go through the normal idempotency table. Heartbeats keep a stable payload version on purpose.
8. **RLS everywhere.** Every tenant-owned table has `ENABLE ROW LEVEL SECURITY` + `FORCE ROW LEVEL SECURITY` + USING/WITH CHECK policies. Every transaction sets `app.tenant_id` / `app.user_id` / `app.request_id` first; connections reset tenant context before returning to the pool; missing tenant context fails closed. Don't write business queries that bypass RLS.
9. **Callback validation order (Java side)**: HMAC → timestamp skew → nonce dedup → `Idempotency-Key` body-hash check → attempt-no match → lease-owner match → tenant/task/meeting-or-document linkage by `taskType` → `expectedInputVersion`. Late callbacks from old attempts/leases must not overwrite newer attempt results.
10. **Lease lifecycle.** Worker sets `leaseOwner` + `leaseExpiresAt`, heartbeats every 15–30s, lease TTL 120s. Expired → `ORPHANED` → re-enqueue. User cancel goes through `CANCEL_PENDING` before `CANCELLED`.
11. **Callback transaction propagation.** The outermost callback transaction is `REQUIRES_NEW`, so a protocol-level exception in an outer filter/interceptor cannot roll back an already-confirmed idempotent response. Long external calls — DashScope, LibreOffice headless, file uploads, ai-worker rerank — must **not** be wrapped in a DB transaction; open short transactions before and after the call instead. Exception → errorCode → HTTP mapping is centralized in `ControllerAdvice` (table in `meeting-api/SPEC.md` §7); controllers throw domain exceptions, they don't build response envelopes.

### Contracts as single source of truth

`packages/meeting-contracts/` is authoritative for:

- HTTP/SSE: `openapi/public-api.yaml`, `openapi/internal-callback-api.yaml`, `openapi/ai-worker-internal-api.yaml`
- MQ: `schemas/rabbitmq/processing-task-message.schema.json` (Java → ai-worker), `schemas/rabbitmq/export-job-message.schema.json` (Java internal `export-queue`)
- Cross-language: `schemas/common/enums.yaml`, `schemas/common/error-codes.yaml`

When changing an enum or error code: edit the YAML first, run `npm run check`, regen with `npm run codegen`, then update hand-written DTOs in `meeting-api-client` to match. `npm run check` validates that Java/TS/Python enum surfaces stay in sync with the YAML and that `pipelineSteps` does not allow Java-owned steps.

All HTTP responses use a unified envelope: `{success, data, error: {code, message, retryable, details}|null, requestId, traceId}`. All non-login writes require `X-Request-Id`, `X-Trace-Id`, `Idempotency-Key`. JSON is camelCase; timestamps are ISO-8601 UTC; audio offsets are millisecond integers (`startMs`/`endMs`); confidence ∈ [0,1]; enum values are SCREAMING_SNAKE.

### Where to put new code

- **New business domain**: create matching packages in `client/`, `domain/`, `app/`, `adapter/`, `infrastructure/persistence/` before adding classes — never park new code in `common/` or `web/`.
- **New cross-app contract** (endpoint, header, enum, error code): change `packages/meeting-contracts/` first, regenerate, then update consumers.
- **New Java callback validation rule**: adapter unpacks headers, app does validation. Adapter must not short-circuit idempotency, but it does split heartbeat vs. real step-update into different app commands (`StepProgressHeartbeatCommand` vs `StepCallbackCommand`).
- **New worker step**: register in `apps/ai-worker/ai_worker/application/workflows/registry.py` (or equivalent), update `pipelineSteps` enum, ensure ai-worker fail-fast covers the new value.
- **New Flyway migration**: `apps/meeting-api/meeting-api-infrastructure/src/main/resources/db/migration/V{yyyyMMddHHmm}__desc.sql`. Don't modify `docs/ddls/`.

### Stack defaults per workspace

Chosen for phase 1 — don't substitute without updating the workspace `SPEC.md` and parent POM/pyproject/package.json:

- **`meeting-api`**: Spring Boot 3.3 · MyBatis-Plus 3.5 + native SQL (no JPA — RLS, `FOR UPDATE SKIP LOCKED`, pgvector queries are explicit SQL) · Flyway 10 · Jackson 2.17 · Logback + Logstash JSON encoder (MDC: `traceId`/`requestId`/`tenantId`/`userId`) · Micrometer + Prometheus · JUnit 5 + Mockito + ArchUnit + Testcontainers + WireMock.
- **`ai-worker`**: FastAPI + Uvicorn (no gunicorn) · **Dramatiq 1.17** WorkerRuntime over RabbitMQ (no Celery / Temporal) · **Prefect 3** WorkflowEngine · LangGraph for agents · `torch` 2.5+ · `pyannote.audio` 3.3+ · `3D-Speaker` · `structlog` + `prometheus-client` · pytest / pytest-asyncio / respx / pytest-benchmark.
- **`meeting-web`**: React 18.3 (no React 19 features) · Vite 5 · TS strict · **TanStack Query** for server state · **Zustand** for cross-page UI state (no Redux) · `react-hook-form` + `zod` (zod schema names align with OpenAPI request schemas) · Vitest + React Testing Library + MSW + Playwright. Access token is **memory-only** (no localStorage / sessionStorage); refresh token is HttpOnly cookie + `X-CSRF-Token`. Transcript lists must virtualize; first-screen JS gzip budget `< 200KB`.

### Cross-workspace MVP slicing

Each workspace `SPEC.md` declares the same incremental ladder. Don't park new work outside the current rung — it makes the boundary tests meaningless:

- **MVP-0** (done): auth + meetings + processing tasks + SSE/polling + worker fake pipeline + callback idempotency.
- **MVP-1** (largely done): audio multipart upload + transcript + minutes/items + STALE cascade + speaker enrollment/confirmation + documents (partial).
- **MVP-2** (code-complete 2026-05-19, pending staging acceptance): RAG (chunk strategy, pgvector + permission recheck, rerank, citation, coverage), exports (async, version-bound, short-link revoke, SSE channel), compliance (legal hold + DELETE endpoint, deletion jobs, deletion certificates, break-glass, audit query), observability/security/perf hardening (RAG phase timers + 429, SafeMarkdown XSS, gitleaks + kubeconform CI, perf-baseline harness, real Qwen3-ASR + pyannote runtime scaffolds, Playwright legal-hold + STALE + upload→SSE→RAG→PDF specs). Remaining work: Phase J staging acceptance — see `docs/runbooks/phase-j-acceptance.md`.

`todo.md` is the live progress ledger — check it before starting work in any phase to avoid duplicating completed items.

### Generated directories (don't edit by hand)

- `apps/meeting-web/src/shared/api/types.gen.ts`
- `apps/ai-worker/ai_worker/generated/`
- `apps/meeting-api/meeting-api-client/generated/`

CI verifies `git diff` is clean after `npm run codegen` — regenerate locally before committing schema changes.

