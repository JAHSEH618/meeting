# Worker-Web Speaker + Upload — Implementation Plan Index

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement each phase task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Source spec:** [`docs/superpowers/specs/2026-05-27-worker-web-speaker-upload-design.md`](../specs/2026-05-27-worker-web-speaker-upload-design.md)

**Goal:** Enable ai-worker-web (Mac dev phase) to enroll new persons (even when absent from Java) and upload audio/reference docs that immediately drive a one-shot pipeline through to DashScope summary — all while keeping Java as the sole DB writer.

**Architecture:** BFF细粒度透传（path A）+ frontend orchestrates. Three new pages replace the wizard. Java gains `POST /api/persons` + generic `POST /api/files` + a `SpeakerAutoConfirmService` invoked by `WorkerPhaseCompletedListener` before LLM phase.

**Tech Stack:** Spring Boot 3.3 / MyBatis-Plus / Flyway / FastAPI / httpx / Dramatiq / React 18 / Vite / TanStack Query / Zustand / Playwright / Vitest.

---

## Phases

| Phase | File | Scope | Blocks |
|---|---|---|---|
| **P1** | [p1-contracts](./2026-05-27-worker-web-impl-p1-contracts.md) | OpenAPI + error codes + codegen + lint | P2/P3/P4 |
| **P2** | [p2-java](./2026-05-27-worker-web-impl-p2-java.md) | Person/Files/AutoConfirm — domain + app + adapter + infra + tests | P5 |
| **P3** | [p3-bff](./2026-05-27-worker-web-impl-p3-bff.md) | ai-worker admin BFF: persons/files routers + enrollment fix + meetings cleanup + tests | P5 |
| **P4** | [p4-frontend](./2026-05-27-worker-web-impl-p4-frontend.md) | ai-worker-web: 3 new pages, MultipartUploader, modal, route refactor, vitest + playwright | P5 |
| **P5** | [p5-integration](./2026-05-27-worker-web-impl-p5-integration.md) | docker-compose end-to-end happy path, docs, screenshots | — |

**Recommended order:** P1 → (P2 ∥ P3 ∥ P4) → P5.
Within each phase, follow tasks in order — they are TDD sequenced.

---

## Cross-Phase Conventions

- **Branch:** `feature/worker-web-speaker-upload` (single branch; one PR per phase preferred but optional)
- **Commit message prefix:**
  - P1 → `contracts:`
  - P2 → `feat(meeting-api):` / `fix(meeting-api):` / `test(meeting-api):`
  - P3 → `feat(ai-worker):` / `test(ai-worker):`
  - P4 → `feat(ai-worker-web):` / `refactor(ai-worker-web):` / `test(ai-worker-web):`
  - P5 → `chore:` / `docs:`
- **Run gates per phase:**
  - P1: `cd packages/meeting-contracts && npm run check && npm run codegen` → `git diff` clean
  - P2: `JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true ./mvnw -DskipITs test` then `./mvnw verify -q`
  - P3: `cd apps/ai-worker && uv run pyright ai_worker/ && uv run pytest tests/ -x -q`
  - P4: `cd apps/ai-worker-web && npx tsc --noEmit && npm test && npm run build && npm run e2e`
  - P5: see P5 doc

---

## Definition of Done

- Spec §10 happy paths both manually walked end-to-end on Mac docker-compose stack
- todo-final.md and apps/ai-worker-web/SPEC.md updated to reference this plan
- All 5 CI jobs green on the feature branch
