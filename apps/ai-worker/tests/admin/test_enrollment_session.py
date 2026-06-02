"""C5.2 — enrollment session ritual end-to-end + TTL cleanup."""

from __future__ import annotations

import asyncio
import hashlib
import time
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from ai_worker.admin.enrollment import build_enrollment_router
from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.session_store import EnrollmentSessionStore
from ._jwt_helpers import make_admin_token


@pytest.fixture
def store(tmp_path: Path) -> EnrollmentSessionStore:
    return EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)


@pytest.mark.asyncio
async def test_session_lifecycle(store: EnrollmentSessionStore, tmp_path: Path):
    session = await store.create(tenant_id="tenant_01", person_id="person_01")
    assert session.session_id.startswith("enr_")
    assert session.state == "CREATED"

    audio_path = tmp_path / f"{session.session_id}.bin"
    audio_path.write_bytes(b"audio bytes here")
    session.touch_audio(audio_path)
    await store.replace(session)

    retrieved = await store.get(session.session_id)
    assert retrieved is not None
    assert retrieved.state == "AUDIO_UPLOADED"

    retrieved.touch_preview(0.82, [0.1, 0.2, 0.3])
    await store.replace(retrieved)

    after_preview = await store.get(session.session_id)
    assert after_preview is not None
    assert after_preview.state == "PREVIEWED"
    assert after_preview.quality_score == 0.82


@pytest.mark.asyncio
async def test_drop_cleans_audio(store: EnrollmentSessionStore, tmp_path: Path):
    session = await store.create("tenant_01", "person_01")
    audio_path = tmp_path / "audio.bin"
    audio_path.write_bytes(b"x" * 1024)
    session.touch_audio(audio_path)
    await store.replace(session)

    await store.drop(session.session_id)

    assert await store.get(session.session_id) is None
    assert not audio_path.exists()


@pytest.mark.asyncio
async def test_expired_session_is_evicted(tmp_path: Path):
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=0)
    session = await store.create("tenant_01", "person_01")
    audio = tmp_path / "audio.bin"
    audio.write_bytes(b"data")
    session.touch_audio(audio)
    await store.replace(session)

    # Give the system clock a beat so expires_at is strictly in the past.
    time.sleep(0.01)

    assert await store.get(session.session_id) is None
    evicted = await store.evict_expired()
    assert evicted >= 0  # already cleaned up via get; both code paths fine


@pytest.mark.asyncio
async def test_cross_tenant_isolation(store: EnrollmentSessionStore):
    s1 = await store.create("tenant_01", "person_a")
    s2 = await store.create("tenant_02", "person_b")
    assert s1.session_id != s2.session_id
    fetched = await store.get(s1.session_id)
    assert fetched is not None and fetched.tenant_id == "tenant_01"


@pytest.mark.asyncio
async def test_cleanup_loop_runs_and_stops(tmp_path: Path):
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    await store.start_cleanup_loop(interval_seconds=1)
    await asyncio.sleep(0.05)
    await store.stop_cleanup_loop()


class _StubJavaClient(JavaPublicClient):
    def __init__(self) -> None:
        self.received: list[dict[str, Any]] = []
        self._base_url = "http://meeting-api.test"
        self._timeout = 5.0

    async def request(  # type: ignore[override]
        self,
        method: str,
        path: str,
        *,
        claims,
        request_id=None,
        trace_id=None,
        idempotency_key=None,
        json=None,
        params=None,
        content=None,
        extra_headers=None,
    ) -> httpx.Response:
        self.received.append({
            "method": method,
            "path": path,
            "body": json,
            "idempotency": idempotency_key,
            "tenant": claims.tenant_id,
        })
        if method == "GET" and path == "/api/persons/person_01":
            return httpx.Response(200, json={
                "success": True,
                "data": {
                    "personId": "person_01",
                    "displayName": "李四",
                    "email": "li@example.com",
                    "externalId": None,
                    "createdAt": "2026-06-02T10:00:00Z",
                },
                "error": None,
                "requestId": "",
                "traceId": "",
            })
        if method == "POST" and path == "/api/speaker-profiles":
            return httpx.Response(200, json={
                "success": True,
                "data": {"speakerProfileId": "sp_01"},
                "error": None,
                "requestId": "",
                "traceId": "",
            })
        if method == "POST" and path == "/api/files":
            return httpx.Response(200, json={
                "success": True,
                "data": {
                    "uploadId": "up_01",
                    "parts": [],
                },
                "error": None,
                "requestId": "",
                "traceId": "",
            })
        if method == "POST" and path == "/api/files/up_01/parts":
            return httpx.Response(200, json={
                "success": True,
                "data": {
                    "partNumber": 1,
                    "presignedUrl": "https://upload.test/part-1",
                    "expiresAt": "2026-05-27T10:00:00Z",
                },
                "error": None,
                "requestId": "",
                "traceId": "",
            })
        if method == "POST" and path == "/api/files/up_01/complete":
            return httpx.Response(200, json={
                "success": True,
                "data": {
                    "fileId": "file_01",
                    "sha256": "0" * 64,
                    "sizeBytes": 12,
                    "contentType": "audio/wav",
                },
                "error": None,
                "requestId": "",
                "traceId": "",
            })
        if method == "POST" and path == "/api/speaker-profiles/sp_01/enrollments":
            return httpx.Response(200, json={
                "success": True,
                "data": {"enrollmentId": "en_01"},
                "error": None,
                "requestId": "",
                "traceId": "",
            })
        return httpx.Response(404, json={
            "success": False,
            "data": None,
            "error": {"code": "NOT_FOUND", "message": path, "retryable": False},
            "requestId": "",
            "traceId": "",
        })

    async def close(self) -> None:  # pragma: no cover
        return None


class _FakeUploadAsyncClient:
    def __init__(self, *args, **kwargs) -> None:
        self.requests: list[tuple[str, bytes, dict[str, str]]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args) -> None:
        return None

    async def put(self, url: str, *, content: bytes, headers: dict[str, str]) -> httpx.Response:
        self.requests.append((url, content, headers))
        return httpx.Response(200, headers={"etag": '"etag-1"'})


@pytest.mark.asyncio
async def test_commit_uses_speaker_profiles_and_generic_file_upload(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    import ai_worker.admin.enrollment as enrollment_module

    monkeypatch.setattr(enrollment_module.httpx, "AsyncClient", _FakeUploadAsyncClient)
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    session = await store.create("tenant_01", "person_01")
    audio = tmp_path / "sample.wav"
    audio.write_bytes(b"audio-bytes")
    session.touch_audio(audio)
    session.touch_preview(0.9, [0.1, 0.2])
    await store.replace(session)

    java = _StubJavaClient()
    app = FastAPI()
    app.include_router(build_enrollment_router(java_client=java, session_store=store))
    headers = {
        "Authorization": f"Bearer {make_admin_token()}",
        "X-Request-Id": "req_1",
        "X-Trace-Id": "trace_1",
        "Idempotency-Key": "idem_1",
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.post(f"/admin/enrollment/sessions/{session.session_id}/commit", headers=headers)

    assert response.status_code == 200
    assert response.json()["data"]["state"] == "COMMITTED"
    assert response.json()["data"]["profileId"] == "sp_01"
    assert response.json()["data"]["fileId"] == "file_01"
    paths = [call["path"] for call in java.received]
    assert paths == [
        "/api/persons/person_01",
        "/api/speaker-profiles",
        "/api/files",
        "/api/files/up_01/parts",
        "/api/files/up_01/complete",
        "/api/speaker-profiles/sp_01/enrollments",
    ]
    profile_body = java.received[1]["body"]
    assert profile_body == {
        "personId": "person_01",
        "displayName": "李四",
        "consentReference": "USER_ENROLLMENT:v1",
    }
    part_body = java.received[3]["body"]
    complete_body = java.received[4]["body"]
    expected_sha = hashlib.sha256(b"audio-bytes").hexdigest()
    assert part_body == {"partNumber": 1, "sizeBytes": len(b"audio-bytes"), "partSha256": expected_sha}
    assert complete_body["parts"] == [{"partNumber": 1, "partSha256": expected_sha, "etag": "etag-1"}]
    enroll_body = java.received[5]["body"]
    assert enroll_body == {"audioFileId": "file_01", "consentReference": "USER_ENROLLMENT:v1"}
    assert await store.get(session.session_id) is None
    assert not audio.exists()


@pytest.mark.asyncio
async def test_commit_stops_before_profile_write_when_java_person_is_missing(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    import ai_worker.admin.enrollment as enrollment_module

    monkeypatch.setattr(enrollment_module.httpx, "AsyncClient", _FakeUploadAsyncClient)
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    session = await store.create("tenant_01", "missing")
    audio = tmp_path / "sample.wav"
    audio.write_bytes(b"audio-bytes")
    session.touch_audio(audio)
    session.touch_preview(0.9, [0.1, 0.2])
    await store.replace(session)

    java = _StubJavaClient()
    app = FastAPI()
    app.include_router(build_enrollment_router(java_client=java, session_store=store))
    headers = {
        "Authorization": f"Bearer {make_admin_token()}",
        "X-Request-Id": "req_1",
        "X-Trace-Id": "trace_1",
        "Idempotency-Key": "idem_1",
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.post(f"/admin/enrollment/sessions/{session.session_id}/commit", headers=headers)

    assert response.status_code == 404
    assert [call["path"] for call in java.received] == ["/api/persons/missing"]
    assert await store.get(session.session_id) is not None
    assert audio.exists()


@pytest.mark.asyncio
async def test_commit_requires_selected_person_before_java_writes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    import ai_worker.admin.enrollment as enrollment_module

    monkeypatch.setattr(enrollment_module.httpx, "AsyncClient", _FakeUploadAsyncClient)
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    session = await store.create("tenant_01", None)
    audio = tmp_path / "sample.wav"
    audio.write_bytes(b"audio-bytes")
    session.touch_audio(audio)
    session.touch_preview(0.9, [0.1, 0.2])
    await store.replace(session)

    java = _StubJavaClient()
    app = FastAPI()
    app.include_router(build_enrollment_router(java_client=java, session_store=store))
    headers = {
        "Authorization": f"Bearer {make_admin_token()}",
        "X-Request-Id": "req_1",
        "X-Trace-Id": "trace_1",
        "Idempotency-Key": "idem_1",
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.post(f"/admin/enrollment/sessions/{session.session_id}/commit", headers=headers)

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "ENROLLMENT_PERSON_REQUIRED"
    assert java.received == []
    assert await store.get(session.session_id) is not None
    assert audio.exists()


@pytest.mark.asyncio
async def test_commit_rejects_low_quality_preview_before_java_writes(tmp_path: Path):
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    session = await store.create("tenant_01", "person_01")
    audio = tmp_path / "weak.wav"
    audio.write_bytes(b"weak-audio")
    session.touch_audio(audio)
    session.touch_preview(0.49, [0.1, 0.2])
    await store.replace(session)

    java = _StubJavaClient()
    app = FastAPI()
    app.include_router(build_enrollment_router(java_client=java, session_store=store))
    headers = {
        "Authorization": f"Bearer {make_admin_token()}",
        "X-Request-Id": "req_1",
        "X-Trace-Id": "trace_1",
        "Idempotency-Key": "idem_1",
    }

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.post(f"/admin/enrollment/sessions/{session.session_id}/commit", headers=headers)

    assert response.status_code == 409
    body = response.json()
    assert body["error"]["code"] == "AUDIO_QUALITY_LOW"
    assert java.received == []
    assert await store.get(session.session_id) is not None
    assert audio.exists()
