# AI Worker Workstation Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Python-hosted ai-worker workstation log in with `admin/admin123` and receive a token accepted by both ai-worker `/admin/*` and Java `/api/*`.

**Architecture:** Java dev auth changes from opaque `mvp0_*` tokens to HS256 JWTs with claims matching ai-worker's `jwt_middleware.py`. The ai-worker workstation adds a local `/workstation/login` page that calls Java `/api/auth/login` through the existing Vite `/api` proxy or Python-hosted same-origin path, stores the returned token in memory, and returns to the workstation.

**Tech Stack:** Java 17, Spring Boot, Jackson, JCA HMAC-SHA256, React 18, Vite 5, Vitest, FastAPI.

---

### Task 1: Java Dev Auth JWT

**Files:**
- Modify: `apps/meeting-api/meeting-api-start/src/test/java/com/meeting/api/AuthControllerTest.java`
- Create: `apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/auth/AdminJwtCodec.java`
- Modify: `apps/meeting-api/meeting-api-app/src/main/java/com/meeting/api/app/auth/InMemoryAuthApplicationService.java`
- Modify: `apps/meeting-api/meeting-api-start/src/main/resources/application.yml`

- [ ] **Step 1: Write failing tests**

Add assertions that `POST /api/auth/login` returns a three-segment JWT, with `aud=ai-worker-admin`, `iss=meeting-api`, `tenantId=tenant_default`, `roles` containing `ADMIN`, and that `GET /api/auth/me` accepts the JWT but rejects a tampered JWT.

- [ ] **Step 2: Verify red**

Run:

```bash
cd apps/meeting-api
./mvnw -pl meeting-api-start -am -Dtest=AuthControllerTest test
```

Expected: fails because current token starts with `mvp0_` and is not a JWT.

- [ ] **Step 3: Implement JWT codec and dev auth integration**

Create a focused `AdminJwtCodec` for HS256 signing and validation. Use `Base64.getUrlEncoder().withoutPadding()`, `Mac.getInstance("HmacSHA256")`, and Jackson for JSON. Update `InMemoryAuthApplicationService.login()` to return JWTs and `authenticate()` to decode JWT claims into `AuthUserDTO`, while retaining the session map path for old opaque tokens during the same JVM.

- [ ] **Step 4: Verify green**

Run the same Maven test and ensure it passes.

### Task 2: ai-worker-web Login Page

**Files:**
- Create: `apps/ai-worker-web/src/pages/LoginPage.tsx`
- Modify: `apps/ai-worker-web/src/App.tsx`
- Modify: `apps/ai-worker-web/src/shared/auth/store.ts`
- Modify: `apps/ai-worker-web/src/shared/auth/store.test.ts`
- Create: `apps/ai-worker-web/src/pages/LoginPage.test.tsx`

- [ ] **Step 1: Write failing tests**

Add tests that:
- `redirectToLogin()` navigates to `/workstation/login?redirect=...` when no external login URL is configured.
- `LoginPage` posts credentials to `/api/auth/login`, stores `data.accessToken`, and displays API errors.

- [ ] **Step 2: Verify red**

Run:

```bash
cd apps/ai-worker-web
npm test -- src/shared/auth/store.test.ts src/pages/LoginPage.test.tsx
```

Expected: fails because there is no `LoginPage` and no local login fallback.

- [ ] **Step 3: Implement minimal login UI**

Add `/login` route under `BrowserRouter basename="/workstation"`. The page uses username/password state, posts to `/api/auth/login`, expects the existing envelope `{ success, data: { accessToken } }`, stores the token via `authStore.set()`, and navigates to the `redirect` query or `/meetings`.

- [ ] **Step 4: Verify green**

Run the same Vitest command and ensure it passes.

### Task 3: Integration Verification And Docs

**Files:**
- Modify: `apps/ai-worker/README.md`
- Modify: `deploy/DEPLOY.md`

- [ ] **Step 1: Document local run commands**

Document that Java and ai-worker must share `AI_WORKER_ADMIN_JWT_SECRET`, `AI_WORKER_ADMIN_JWT_AUDIENCE`, and `AI_WORKER_ADMIN_JWT_ISSUER`.

- [ ] **Step 2: Run verification**

Run:

```bash
cd apps/meeting-api
./mvnw -pl meeting-api-start -am -Dtest=AuthControllerTest test

cd ../../apps/ai-worker-web
npm test
npm run type-check
npm run build

cd ../ai-worker
env UV_CACHE_DIR=/private/tmp/uv-cache uv run pytest tests/test_workstation_mount.py -q
```

Expected: all commands pass.
