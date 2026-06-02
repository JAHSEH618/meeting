"""C5.3 — meeting workstation passthrough routes for one-shot pipeline."""

from __future__ import annotations

from typing import Any

import httpx
import pytest

from ai_worker.admin.java_client import JavaPublicClient
from ai_worker.admin.router import build_admin_router
from ai_worker.admin.session_store import EnrollmentSessionStore
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from ._jwt_helpers import make_admin_token


def _client(app: FastAPI) -> AsyncClient:
    return AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation")


class _StubJavaClient(JavaPublicClient):
    """Override the network layer with an in-memory route table."""

    def __init__(self) -> None:
        self.received: list[dict[str, Any]] = []
        # Bypass parent __init__ which constructs an httpx client + asserts base_url
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
            "params": dict(params) if params else None,
            "idempotency": idempotency_key,
            "tenant": claims.tenant_id,
        })
        return self._respond(method, path)

    def _respond(self, method: str, path: str) -> httpx.Response:
        if path == "/api/meetings" and method == "GET":
            return httpx.Response(200, json={
                "success": True,
                "data": [
                    {"meetingId": "m_01", "title": "测试会议",
                     "securityLevel": "INTERNAL", "status": "READY",
                     "language": "zh", "createdAt": "2026-05-27T10:00:00Z"},
                ],
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path == "/api/meetings/m_01" and method == "GET":
            return httpx.Response(200, json={
                "success": True,
                "data": {
                    "meetingId": "m_01",
                    "title": "测试会议",
                    "securityLevel": "INTERNAL",
                    "status": "READY",
                    "language": "zh",
                    "createdAt": "2026-05-27T10:00:00Z",
                },
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path.startswith("/api/meetings/") and path.endswith("/processing-tasks/latest"):
            return httpx.Response(200, json={
                "success": True,
                "data": {"taskId": "task_01", "meetingId": "m_01", "phase": "WORKER_DAG_DONE",
                          "status": "RUNNING", "attemptNo": 1, "steps": []},
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path == "/api/meetings/m_01/speakers" and method == "GET":
            return httpx.Response(200, json={
                "success": True,
                "data": {
                    "meetingId": "m_01",
                    "speakers": [
                        {
                            "speakerLabel": "SPEAKER_01",
                            "displayName": "李四",
                            "personId": "p_01",
                            "speakerProfileId": "sp_01",
                            "confirmationStatus": "MANUALLY_CONFIRMED",
                            "candidates": [],
                        },
                    ],
                },
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path == "/api/meetings/m_01/minutes" and method == "GET":
            return httpx.Response(200, json={
                "success": True,
                "data": {"meetingId": "m_01", "markdown": "## 纪要"},
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path == "/api/meetings/m_01/files/audio/uploads" and method == "POST":
            return httpx.Response(200, json={
                "success": True,
                "data": {"uploadId": "up_01", "parts": []},
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path == "/api/meetings/m_01/files/audio/uploads/up_01/parts" and method == "POST":
            return httpx.Response(200, json={
                "success": True,
                "data": {"uploadId": "up_01", "partNumber": 1, "uploadUrl": "https://upload.test/1"},
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path == "/api/meetings/m_01/files/audio/uploads/up_01/complete" and method == "POST":
            return httpx.Response(200, json={
                "success": True,
                "data": {"uploadId": "up_01", "uploadStatus": "COMPLETED", "fileId": "file_01"},
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path == "/api/meetings/m_01/files/audio/uploads/up_01/abort" and method == "POST":
            return httpx.Response(200, json={
                "success": True,
                "data": None,
                "error": None,
                "requestId": "", "traceId": "",
            })
        return httpx.Response(200, json={"success": True, "data": {}, "error": None,
                                          "requestId": "", "traceId": ""})

    async def close(self) -> None:  # pragma: no cover — not exercised in tests
        return None


@pytest.fixture
def app(tmp_path) -> FastAPI:
    application = FastAPI()
    stub = _StubJavaClient()
    application.state.java_stub = stub  # keep ref for assertions
    store = EnrollmentSessionStore(tmp_dir=str(tmp_path), ttl_seconds=3600)
    application.include_router(build_admin_router(java_client=stub, session_store=store))
    return application


@pytest.fixture
def auth_headers() -> dict[str, str]:
    return {
        "Authorization": f"Bearer {make_admin_token()}",
        "X-Request-Id": "req_t1",
        "X-Trace-Id": "trace_t1",
        "Idempotency-Key": "idem_t1",
    }


@pytest.mark.asyncio
async def test_audio_upload_complete_passthrough_does_not_start_or_finalize(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.post(
            "/admin/meetings/m_01/files/audio/uploads/up_01/complete",
            json={
                "fileSha256": "a" * 64,
                "parts": [{"partNumber": 1, "partSha256": "a" * 64, "etag": "e"}],
            },
            headers=auth_headers,
        )
    assert response.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    assert stub.received == [{
        "method": "POST",
        "path": "/api/meetings/m_01/files/audio/uploads/up_01/complete",
        "body": {
            "fileSha256": "a" * 64,
            "parts": [{"partNumber": 1, "partSha256": "a" * 64, "etag": "e"}],
        },
        "params": None,
        "idempotency": "idem_t1",
        "tenant": "tenant_01",
    }]


@pytest.mark.asyncio
async def test_missing_jwt_returns_401(app: FastAPI):
    async with _client(app) as client:
        response = await client.post("/admin/meetings/m_01/files/audio/uploads/up_01/complete")
    assert response.status_code == 401
    body = response.json()
    assert body["detail"]["code"] == "UNAUTHENTICATED"


@pytest.mark.asyncio
async def test_manual_start_and_finalize_routes_are_removed(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        start = await client.post(
            "/admin/meetings/m_01:start-processing",
            json={"options": {"enableAsr": True}},
            headers=auth_headers,
        )
        finalize = await client.post("/admin/meetings/m_01:finalize", headers=auth_headers)

    assert start.status_code in {404, 405}
    assert finalize.status_code in {404, 405}
    stub: _StubJavaClient = app.state.java_stub
    assert not any(call["path"].endswith("/processing-tasks") for call in stub.received)
    assert not any(call["path"].endswith(":resume-java-phase") for call in stub.received)


@pytest.mark.asyncio
async def test_audio_upload_init_and_part_passthrough(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        init = await client.post(
            "/admin/meetings/m_01/files/audio/uploads",
            json={"fileName": "meeting.wav", "contentType": "audio/wav", "fileSizeBytes": 1, "fileSha256": "a" * 64},
            headers=auth_headers,
        )
        part = await client.post(
            "/admin/meetings/m_01/files/audio/uploads/up_01/parts",
            json={"partNumber": 1, "sizeBytes": 1, "partSha256": "a" * 64},
            headers=auth_headers,
        )
    assert init.status_code == 200
    assert part.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    paths = [c["path"] for c in stub.received]
    assert paths == [
        "/api/meetings/m_01/files/audio/uploads",
        "/api/meetings/m_01/files/audio/uploads/up_01/parts",
    ]


@pytest.mark.asyncio
async def test_attach_document_passthrough(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.post(
            "/admin/meetings/m_01/documents:attach",
            json={"documentId": "doc_01", "role": "REFERENCE"},
            headers=auth_headers,
        )
    assert response.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    attach = next(c for c in stub.received if c["path"] == "/api/meetings/m_01/documents")
    assert attach["body"] == {"documentId": "doc_01", "role": "REFERENCE"}


@pytest.mark.asyncio
async def test_create_document_passthrough(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.post(
            "/admin/documents",
            json={
                "title": "ref.pdf",
                "fileId": "file_01",
                "documentType": "PDF",
                "securityLevel": "INTERNAL",
                "contentHash": "a" * 64,
            },
            headers=auth_headers,
        )
    assert response.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    create = next(c for c in stub.received if c["path"] == "/api/documents")
    assert create["method"] == "POST"
    assert create["body"] == {
        "title": "ref.pdf",
        "fileId": "file_01",
        "documentType": "PDF",
        "securityLevel": "INTERNAL",
        "contentHash": "a" * 64,
    }
    assert create["idempotency"] == "idem_t1"


@pytest.mark.asyncio
async def test_list_meetings_proxies_java_public_api(app: FastAPI, auth_headers: dict[str, str]):
    """Iceberg refactor — landing page wires through to /api/meetings."""
    async with _client(app) as client:
        response = await client.get("/admin/meetings", headers={
            "Authorization": auth_headers["Authorization"],
            "X-Request-Id": auth_headers["X-Request-Id"],
            "X-Trace-Id": auth_headers["X-Trace-Id"],
        })
    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert isinstance(body["data"], list)
    assert body["data"][0]["meetingId"] == "m_01"
    stub: _StubJavaClient = app.state.java_stub
    list_call = next(c for c in stub.received if c["method"] == "GET" and c["path"] == "/api/meetings")
    assert list_call["tenant"] == "tenant_01"


@pytest.mark.asyncio
async def test_get_meeting_aggregate_unwraps_java_data_for_workstation(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.get("/admin/meetings/m_01", headers={
            "Authorization": auth_headers["Authorization"],
            "X-Request-Id": auth_headers["X-Request-Id"],
            "X-Trace-Id": auth_headers["X-Trace-Id"],
        })

    assert response.status_code == 200
    body = response.json()
    assert body["success"] is True
    assert body["data"]["meeting"]["meetingId"] == "m_01"
    assert "success" not in body["data"]["meeting"]
    assert body["data"]["latestTask"]["taskId"] == "task_01"
    assert body["data"]["speakers"] == {
        "meetingId": "m_01",
        "speakers": [
            {
                "speakerLabel": "SPEAKER_01",
                "displayName": "李四",
                "personId": "p_01",
                "speakerProfileId": "sp_01",
                "confirmationStatus": "MANUALLY_CONFIRMED",
                "candidates": [],
            },
        ],
    }
    assert body["data"]["minutes"]["meetingId"] == "m_01"
    stub: _StubJavaClient = app.state.java_stub
    assert [c["path"] for c in stub.received] == [
        "/api/meetings/m_01",
        "/api/meetings/m_01/processing-tasks/latest",
        "/api/meetings/m_01/speakers",
        "/api/meetings/m_01/minutes",
    ]


@pytest.mark.asyncio
async def test_create_meeting_forwards_participants_to_java(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.post(
            "/admin/meetings",
            json={
                "title": "季度评审",
                "scheduledStartAt": "2026-06-03T09:30:00Z",
                "securityLevel": "INTERNAL",
                "language": "zh",
                "participants": [
                    {"personId": "p_01", "displayName": "李四", "role": "PARTICIPANT"},
                    {"personId": "p_02", "displayName": "王五", "role": "PARTICIPANT"},
                ],
            },
            headers=auth_headers,
        )

    assert response.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    create = next(c for c in stub.received if c["method"] == "POST" and c["path"] == "/api/meetings")
    assert create["body"] == {
        "title": "季度评审",
        "scheduledStartAt": "2026-06-03T09:30:00Z",
        "securityLevel": "INTERNAL",
        "language": "zh",
        "participants": [
            {"personId": "p_01", "displayName": "李四", "role": "PARTICIPANT"},
            {"personId": "p_02", "displayName": "王五", "role": "PARTICIPANT"},
        ],
    }
    assert create["idempotency"] == "idem_t1"
    assert create["tenant"] == "tenant_01"


@pytest.mark.asyncio
async def test_update_meeting_forwards_participants_to_java(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.patch(
            "/admin/meetings/m_01",
            json={
                "scheduledStartAt": "2026-06-04T10:00:00Z",
                "participants": [
                    {"personId": "p_01", "displayName": "李四", "role": "PARTICIPANT"},
                    {"personId": "p_02", "displayName": "王五", "role": "PARTICIPANT"},
                ],
                "expectedVersion": 3,
            },
            headers=auth_headers,
        )

    assert response.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    update = next(c for c in stub.received if c["method"] == "PATCH" and c["path"] == "/api/meetings/m_01")
    assert update["body"] == {
        "scheduledStartAt": "2026-06-04T10:00:00Z",
        "participants": [
            {"personId": "p_01", "displayName": "李四", "role": "PARTICIPANT"},
            {"personId": "p_02", "displayName": "王五", "role": "PARTICIPANT"},
        ],
        "expectedVersion": 3,
    }
    assert update["idempotency"] == "idem_t1"
    assert update["tenant"] == "tenant_01"


@pytest.mark.asyncio
async def test_reject_speaker_passthrough_to_java(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.post(
            "/admin/meetings/m_01/speakers/SPEAKER_01:reject",
            headers=auth_headers,
        )

    assert response.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    reject = next(c for c in stub.received if c["method"] == "POST" and c["path"].endswith("/reject"))
    assert reject["path"] == "/api/meetings/m_01/speakers/SPEAKER_01/reject"
    assert reject["body"] is None
    assert reject["idempotency"] == "idem_t1"
    assert reject["tenant"] == "tenant_01"
