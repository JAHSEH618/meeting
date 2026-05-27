# P3 — ai-worker admin BFF Implementation

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Add `/admin/persons` + `/admin/files/*` BFF routers; fix enrollment commit URL & body; remove start-processing/finalize transparency (Java auto-creates ProcessingTask + auto-runs LLM phase now); update existing tests.

**Working dir:** `apps/ai-worker/`

**Pre-flight:** P1 contracts merged + codegen current. `uv sync --extra dev` done.

**Run tests:** `uv run pytest tests/ -x -q`
**Type check:** `uv run pyright ai_worker/`

---

### Task 1: `admin/persons.py` — new router (TDD)

**Files:**
- Create: `apps/ai-worker/tests/admin/test_persons.py`
- Create: `apps/ai-worker/ai_worker/admin/persons.py`

- [ ] **Step 1: Failing test**

```python
# tests/admin/test_persons.py
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock
import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_worker.admin.persons import build_persons_router
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency


def _app(client_mock):
    app = FastAPI()
    app.include_router(build_persons_router(java_client=client_mock))
    app.dependency_overrides[admin_claims_dependency] = lambda: AdminClaims(
        sub="u1", tenant_id="t1", role="admin"
    )
    return TestClient(app)


def _resp(status: int, body: bytes = b'{"success":true,"data":{}}') -> httpx.Response:
    return httpx.Response(status_code=status, content=body, headers={"content-type": "application/json"})


def test_search_passthrough():
    client = MagicMock()
    client.request = AsyncMock(return_value=_resp(200, b'{"success":true,"data":[{"personId":"p1","displayName":"李四"}]}'))
    body = _app(client).get("/admin/persons?q=lis", headers={"X-Request-Id": "r1", "X-Trace-Id": "tr1"})
    assert body.status_code == 200
    assert "李四" in body.text
    client.request.assert_awaited_once()
    assert client.request.await_args.args[1] == "/api/persons"
    assert client.request.await_args.kwargs["params"] == {"q": "lis"}


def test_create_passthrough_happy():
    client = MagicMock()
    client.request = AsyncMock(return_value=_resp(200, b'{"success":true,"data":{"personId":"p2","displayName":"张三"}}'))
    body = _app(client).post(
        "/admin/persons",
        json={"displayName": "张三"},
        headers={"X-Request-Id": "r1", "X-Trace-Id": "tr1", "Idempotency-Key": "i1"},
    )
    assert body.status_code == 200
    args = client.request.await_args
    assert args.args[1] == "/api/persons"
    assert args.kwargs["json"] == {"displayName": "张三"}
    assert args.kwargs["idempotency_key"] == "i1"


def test_create_409_duplicate_passthrough():
    client = MagicMock()
    client.request = AsyncMock(return_value=_resp(409,
        b'{"success":false,"error":{"code":"PERSON_DUPLICATE","message":"dup","retryable":false,"details":{"matches":[{"personId":"p1","displayName":"张三"}]}}}'))
    body = _app(client).post(
        "/admin/persons",
        json={"displayName": "张三"},
        headers={"X-Request-Id": "r1", "X-Trace-Id": "tr1", "Idempotency-Key": "i1"},
    )
    assert body.status_code == 409
    assert "PERSON_DUPLICATE" in body.text
```

- [ ] **Step 2: Run failing test**

```bash
cd apps/ai-worker
uv run pytest tests/admin/test_persons.py -x -q
```

Expected: FAIL — module `ai_worker.admin.persons` does not exist.

- [ ] **Step 3: Implement router**

```python
# ai_worker/admin/persons.py
"""/admin/persons — thin BFF transparency for Java's POST/GET /api/persons."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Header, Request

from ai_worker.admin.envelopes import passthrough
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency


def build_persons_router(*, java_client: JavaPublicClient) -> APIRouter:
    router = APIRouter(prefix="/admin/persons", tags=["admin-persons"])

    @router.get("", status_code=200)
    async def search(
        q: str | None = None,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
    ):
        resp = await java_client.request(
            "GET", "/api/persons",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            params={"q": q} if q else {},
        )
        return passthrough(resp.status_code, resp.content, x_request_id, x_trace_id)

    @router.post("", status_code=200)
    async def create(
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        resp = await java_client.request(
            "POST", "/api/persons",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(resp.status_code, resp.content, x_request_id, x_trace_id)

    return router
```

- [ ] **Step 4: Test passes**

```bash
uv run pytest tests/admin/test_persons.py -x -q
uv run pyright ai_worker/admin/persons.py
```

Expected: 3 passed; 0 pyright errors.

- [ ] **Step 5: Commit**

```bash
git add apps/ai-worker/ai_worker/admin/persons.py apps/ai-worker/tests/admin/test_persons.py
git commit -m "feat(ai-worker): /admin/persons BFF (search + create + dup passthrough)"
```

---

### Task 2: `admin/files.py` — new router (TDD)

**Files:**
- Create: `apps/ai-worker/tests/admin/test_files.py`
- Create: `apps/ai-worker/ai_worker/admin/files.py`

- [ ] **Step 1: Failing test**

```python
# tests/admin/test_files.py
from unittest.mock import AsyncMock, MagicMock
import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_worker.admin.files import build_files_router
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency


def _app(client_mock):
    app = FastAPI()
    app.include_router(build_files_router(java_client=client_mock))
    app.dependency_overrides[admin_claims_dependency] = lambda: AdminClaims(sub="u1", tenant_id="t1", role="admin")
    return TestClient(app)


def _ok(status=200, body=b'{"success":true,"data":{}}'):
    return httpx.Response(status_code=status, content=body, headers={"content-type": "application/json"})


def test_init_upload_passthrough():
    client = MagicMock(); client.request = AsyncMock(return_value=_ok(200, b'{"success":true,"data":{"uploadId":"u1","parts":[]}}'))
    r = _app(client).post("/admin/files/uploads", json={"fileName": "ref.pdf", "contentType": "application/pdf", "fileSizeBytes": 4, "fileSha256": "a"*64},
                         headers={"X-Request-Id": "r1", "X-Trace-Id": "t1", "Idempotency-Key": "i1"})
    assert r.status_code == 200
    args = client.request.await_args
    assert args.args[1] == "/api/files"
    assert args.kwargs["idempotency_key"] == "i1"


def test_part_passthrough():
    client = MagicMock(); client.request = AsyncMock(return_value=_ok(200))
    r = _app(client).post("/admin/files/uploads/u1/parts", json={"partNumber": 1, "sizeBytes": 4, "partSha256": "b"*64},
                         headers={"X-Request-Id": "r1", "X-Trace-Id": "t1", "Idempotency-Key": "i1"})
    assert r.status_code == 200
    assert client.request.await_args.args[1] == "/api/files/u1/parts"


def test_complete_passthrough():
    client = MagicMock(); client.request = AsyncMock(return_value=_ok(200, b'{"success":true,"data":{"fileId":"f1","sha256":"x","sizeBytes":4,"contentType":"application/pdf"}}'))
    r = _app(client).post("/admin/files/uploads/u1/complete", json={"fileSha256": "c"*64, "parts": [{"partNumber":1,"partSha256":"b"*64,"etag":"e"}]},
                         headers={"X-Request-Id": "r1", "X-Trace-Id": "t1", "Idempotency-Key": "i1"})
    assert r.status_code == 200
    assert "fileId" in r.text


def test_abort_passthrough():
    client = MagicMock(); client.request = AsyncMock(return_value=_ok(200))
    r = _app(client).post("/admin/files/uploads/u1/abort",
                         headers={"X-Request-Id": "r1", "X-Trace-Id": "t1", "Idempotency-Key": "i1"})
    assert r.status_code == 200
    assert client.request.await_args.args[1] == "/api/files/u1/abort"


def test_mime_not_allowed_passthrough():
    client = MagicMock(); client.request = AsyncMock(return_value=_ok(415,
        b'{"success":false,"error":{"code":"FILE_MIME_NOT_ALLOWED","message":"not allowed","retryable":false,"details":null}}'))
    r = _app(client).post("/admin/files/uploads", json={"fileName": "x.exe", "contentType": "application/x-msdownload", "fileSizeBytes": 1, "fileSha256": "z"*64},
                         headers={"X-Request-Id": "r1", "X-Trace-Id": "t1", "Idempotency-Key": "i1"})
    assert r.status_code == 415
    assert "FILE_MIME_NOT_ALLOWED" in r.text
```

- [ ] **Step 2: Fail**

```bash
uv run pytest tests/admin/test_files.py -x -q
```

Expected: FAIL — module does not exist.

- [ ] **Step 3: Implement**

```python
# ai_worker/admin/files.py
"""/admin/files — generic multipart upload BFF (PDF/docx/pptx/txt/md). Audio uses /admin/.../audio-uploads instead."""
from __future__ import annotations

from fastapi import APIRouter, Depends, Header, Request

from ai_worker.admin.envelopes import passthrough
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency


def build_files_router(*, java_client: JavaPublicClient) -> APIRouter:
    router = APIRouter(prefix="/admin/files", tags=["admin-files"])

    @router.post("/uploads", status_code=200)
    async def init(
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        resp = await java_client.request(
            "POST", "/api/files",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(resp.status_code, resp.content, x_request_id, x_trace_id)

    @router.post("/uploads/{upload_id}/parts", status_code=200)
    async def part(
        upload_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        resp = await java_client.request(
            "POST", f"/api/files/{upload_id}/parts",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(resp.status_code, resp.content, x_request_id, x_trace_id)

    @router.post("/uploads/{upload_id}/complete", status_code=200)
    async def complete(
        upload_id: str,
        request: Request,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        body = await request.json()
        resp = await java_client.request(
            "POST", f"/api/files/{upload_id}/complete",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key, json=body,
        )
        return passthrough(resp.status_code, resp.content, x_request_id, x_trace_id)

    @router.post("/uploads/{upload_id}/abort", status_code=200)
    async def abort(
        upload_id: str,
        claims: AdminClaims = Depends(admin_claims_dependency),
        x_request_id: str | None = Header(None, alias="X-Request-Id"),
        x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
        idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    ):
        resp = await java_client.request(
            "POST", f"/api/files/{upload_id}/abort",
            claims=claims, request_id=x_request_id, trace_id=x_trace_id,
            idempotency_key=idempotency_key,
        )
        return passthrough(resp.status_code, resp.content, x_request_id, x_trace_id)

    return router
```

- [ ] **Step 4: Test passes**

```bash
uv run pytest tests/admin/test_files.py -x -q
uv run pyright ai_worker/admin/files.py
```

Expected: 5 passed; 0 pyright errors.

- [ ] **Step 5: Commit**

```bash
git add apps/ai-worker/ai_worker/admin/files.py apps/ai-worker/tests/admin/test_files.py
git commit -m "feat(ai-worker): /admin/files BFF (init/parts/complete/abort)"
```

---

### Task 3: Wire new routers into router.py

**Files:**
- Modify: `apps/ai-worker/ai_worker/admin/router.py`

- [ ] **Step 1: Add imports and include_router calls**

Replace the file content:

```python
"""Build the admin router and provide a fail-fast factory hook."""

from __future__ import annotations

from fastapi import APIRouter

from ai_worker.admin.enrollment import build_enrollment_router, build_voiceprint_router
from ai_worker.admin.files import build_files_router
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.meetings import build_meetings_router
from ai_worker.admin.persons import build_persons_router
from ai_worker.admin.session_store import EnrollmentSessionStore, enrollment_session_store
from ai_worker.common.config import settings


class AdminStartupConfigError(RuntimeError):
    """Raised at boot when required configuration for the admin BFF is missing."""


def ensure_admin_config() -> None:
    missing: list[str] = []
    if not settings.java_api_base_url:
        missing.append("AI_WORKER_JAVA_API_BASE_URL")
    if missing:
        raise AdminStartupConfigError(
            "Admin BFF cannot start; missing env: " + ", ".join(missing)
        )


def build_admin_router(
    *,
    java_client: JavaPublicClient | None = None,
    session_store: EnrollmentSessionStore | None = None,
) -> APIRouter:
    client = java_client or JavaPublicClient()
    store = session_store or enrollment_session_store
    parent = APIRouter()
    parent.include_router(build_enrollment_router(java_client=client, session_store=store))
    parent.include_router(build_voiceprint_router(java_client=client))
    parent.include_router(build_persons_router(java_client=client))
    parent.include_router(build_files_router(java_client=client))
    parent.include_router(build_meetings_router(java_client=client))
    return parent
```

- [ ] **Step 2: Smoke test app boot**

```bash
uv run pyright ai_worker/admin/router.py
uv run pytest tests/admin/ -x -q
```

Expected: 0 pyright errors; all admin tests pass.

- [ ] **Step 3: Commit**

```bash
git add apps/ai-worker/ai_worker/admin/router.py
git commit -m "feat(ai-worker): mount persons + files routers"
```

---

### Task 4: Fix `admin/enrollment.py` commit URLs and body

**Files:**
- Modify: `apps/ai-worker/ai_worker/admin/enrollment.py`
- Modify: `apps/ai-worker/tests/admin/test_enrollment_session.py`

- [ ] **Step 1: Update existing test expectations**

Open `tests/admin/test_enrollment_session.py`. Find any place that asserts the three commit URLs (`/api/speakers/profiles`, `/api/speakers/profiles/audio:upload`, `/api/speakers/enrollments`) and change them to the real Java URLs:

| Old expected | New expected |
|---|---|
| `POST /api/speakers/profiles` | `POST /api/speaker-profiles` |
| `POST /api/speakers/profiles/audio:upload` | (drop — see Step 2 below) |
| `POST /api/speakers/enrollments` | `POST /api/speaker-profiles/{profileId}/enrollments` |

Add an assertion that the profile-create body now includes `displayName`/`consentSource`/`consentVersion`.

- [ ] **Step 2: Decide the audio:upload step**

Java's `POST /api/speaker-profiles/{profileId}/enrollments` requires a `sourceAudioFileId` (see `SpeakerProfileController.CreateEnrollmentRequest`). That fileId must come from somewhere durable.

Two options:
- (a) Reuse the new generic `/api/files` upload from P2.B: in commit, first init+parts+complete a generic upload, then pass the returned fileId to enrollments.
- (b) Java adds a tiny `POST /api/speaker-profiles/{profileId}/audio:upload` endpoint that accepts a raw multipart body and returns a fileId.

Pick **(a)** — keeps the contracts clean and reuses generic upload. Update enrollment commit accordingly:

```python
# in admin/enrollment.py @router.post("/sessions/{session_id}/commit"):
# Replace the three-step orchestration body with:

# 1. create profile (path fix)
profile = await java_client.request(
    "POST", "/api/speaker-profiles",
    claims=claims, request_id=x_request_id, trace_id=x_trace_id,
    idempotency_key=f"{idempotency_key or session_id}:profile",
    json={
        "personId": session.person_id,
        "displayName": session.person_id,  # caller can override later; UI passes displayName via personId search anyway
        "consentSource": "workstation",
        "consentVersion": "v1",
    },
)
if profile.status_code >= 400:
    return passthrough(profile.status_code, profile.content, x_request_id, x_trace_id)
profile_id = profile.json().get("data", {}).get("profileId") or profile.json().get("data", {}).get("speakerProfileId")

# 2. upload audio via generic /api/files
import hashlib
audio_bytes = session.audio_path.read_bytes()
sha = hashlib.sha256(audio_bytes).hexdigest()
init = await java_client.request(
    "POST", "/api/files",
    claims=claims, request_id=x_request_id, trace_id=x_trace_id,
    idempotency_key=f"{idempotency_key or session_id}:init",
    json={"fileName": f"enroll-{session_id}.wav", "contentType": "audio/wav",
          "fileSizeBytes": len(audio_bytes), "fileSha256": sha},
)
if init.status_code >= 400:
    return passthrough(init.status_code, init.content, x_request_id, x_trace_id)
upload_id = init.json()["data"]["uploadId"]
parts_meta = init.json()["data"]["parts"]
# PUT to presigned URL of part 1 (single part for short enrollment clips)
import httpx
async with httpx.AsyncClient(timeout=60) as h:
    put_resp = await h.put(parts_meta[0]["presignedUrl"], content=audio_bytes,
                            headers={"Content-Type": "audio/wav"})
    if put_resp.status_code >= 400:
        return passthrough(500, b'{"success":false,"error":{"code":"FILE_UPLOAD_FAILED","retryable":true,"message":"presign put failed"}}',
                           x_request_id, x_trace_id)
    etag = put_resp.headers.get("etag", "").strip('"')
complete = await java_client.request(
    "POST", f"/api/files/{upload_id}/complete",
    claims=claims, request_id=x_request_id, trace_id=x_trace_id,
    idempotency_key=f"{idempotency_key or session_id}:complete",
    json={"fileSha256": sha, "parts": [{"partNumber": 1, "partSha256": sha, "etag": etag}]},
)
if complete.status_code >= 400:
    return passthrough(complete.status_code, complete.content, x_request_id, x_trace_id)
file_id = complete.json()["data"]["fileId"]

# 3. record enrollment under profile
enrollment = await java_client.request(
    "POST", f"/api/speaker-profiles/{profile_id}/enrollments",
    claims=claims, request_id=x_request_id, trace_id=x_trace_id,
    idempotency_key=f"{idempotency_key or session_id}:enroll",
    json={"sourceAudioFileId": file_id},
)
if enrollment.status_code >= 400:
    return passthrough(enrollment.status_code, enrollment.content, x_request_id, x_trace_id)

artifacts = {"profileResponse": profile.text, "completeResponse": complete.text, "enrollmentResponse": enrollment.text}
session.touch_committed(artifacts)
await session_store.replace(session)
await session_store.drop(session_id)
return ok({"sessionId": session_id, "state": "COMMITTED", "profileId": profile_id, "fileId": file_id}, x_request_id, x_trace_id)
```

(Audio MIME may not be in P2 file MIME whitelist. Decision: extend the whitelist to include `audio/wav`, `audio/mpeg`, `audio/x-m4a` in P2.B Task 8 `MIME_WHITELIST` so this works. Add a follow-up commit if needed.)

- [ ] **Step 3: Extend Java MIME whitelist to include audio types**

In `GenericFileUploadApplicationService.java` (P2 Task 8 output), update the constant:

```java
private static final Set<String> MIME_WHITELIST = Set.of(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "text/plain",
    "text/markdown",
    "audio/wav",
    "audio/mpeg",
    "audio/x-m4a",
    "audio/flac"
);
```

Also update the OpenAPI `CreateFileUploadRequest.contentType.enum` (P1 Task 3) to include the same audio types — run `npm run check && npm run codegen` and commit the regen.

- [ ] **Step 4: Run all tests**

```bash
uv run pytest tests/admin/ -x -q
uv run pyright ai_worker/admin/
```

Expected: all pass. 0 pyright errors.

- [ ] **Step 5: Commit**

```bash
git add apps/ai-worker/ai_worker/admin/enrollment.py apps/ai-worker/tests/admin/test_enrollment_session.py
git commit -m "fix(ai-worker): enrollment commit aligns with Java speaker-profiles + generic file upload"
```

---

### Task 5: Clean up `admin/meetings.py` — drop start-processing & finalize

**Files:**
- Modify: `apps/ai-worker/ai_worker/admin/meetings.py`
- Modify: `apps/ai-worker/tests/admin/test_meeting_orchestration.py`

- [ ] **Step 1: Update test expectations**

In `test_meeting_orchestration.py`, remove tests that assert `hold_at_worker_phase=true` injection at start-processing and that assert finalize chain. Replace with a single assertion that **after** audio upload `complete`, the only Java call made is to the audio complete endpoint (Java auto-creates ProcessingTask).

- [ ] **Step 2: Modify `meetings.py`**

Open `ai_worker/admin/meetings.py`. Remove these route handlers entirely:
- `:start-processing` (formerly C3.7 — Java now auto-creates task)
- `:finalize` (formerly C3.9 — no manual finalize in one-shot mode)
- `resume-java-phase` proxy if present

Keep: aggregate GET, speakers confirm, exports, documents/glossary passthroughs. The `searchPersons` you previously called via `/admin/persons?q=` is now served by Task 1's `persons.py`, so remove any persons handler from `meetings.py` if it exists there.

- [ ] **Step 3: Run tests**

```bash
uv run pytest tests/admin/ -x -q
uv run pyright ai_worker/admin/
```

Expected: PASS. 0 pyright errors.

- [ ] **Step 4: Commit**

```bash
git add apps/ai-worker/ai_worker/admin/meetings.py apps/ai-worker/tests/admin/test_meeting_orchestration.py
git commit -m "refactor(ai-worker): drop :start-processing/:finalize (one-shot pipeline)"
```

---

### Task 6: Phase gate — P3 final verify

- [ ] **Step 1: Full test suite**

```bash
cd apps/ai-worker
uv run pytest tests/ -x -q
```

Expected: BUILD SUCCESS — all tests pass.

- [ ] **Step 2: Pyright**

```bash
uv run pyright ai_worker/
```

Expected: 0 errors, 0 warnings.

- [ ] **Step 3: Import smoke**

```bash
uv run python -c "from ai_worker.admin.router import build_admin_router; print('ok')"
```

Expected: `ok`.

**P3 complete.**
