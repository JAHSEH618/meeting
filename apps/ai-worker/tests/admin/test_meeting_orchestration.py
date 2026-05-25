"""C5.3 — meeting workstation orchestration: hold flag + finalize → resume."""

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
        if path.startswith("/api/meetings/") and path.endswith("/processing-tasks/latest"):
            return httpx.Response(200, json={
                "success": True,
                "data": {"taskId": "task_01", "meetingId": "m_01", "phase": "WORKER_DAG_DONE",
                          "status": "RUNNING", "attemptNo": 1, "steps": []},
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path.startswith("/api/processing-tasks/") and path.endswith(":resume-java-phase"):
            return httpx.Response(200, json={
                "success": True,
                "data": {"taskId": "task_01", "meetingId": "m_01", "phase": "JAVA_LLM_RUNNING",
                          "status": "RUNNING", "attemptNo": 1, "steps": []},
                "error": None,
                "requestId": "", "traceId": "",
            })
        if path.startswith("/api/meetings/") and path.endswith("/processing-tasks") and method == "POST":
            return httpx.Response(200, json={
                "success": True,
                "data": {"taskId": "task_new", "phase": "WORKER_DAG_RUNNING"},
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
async def test_start_processing_injects_hold_flag(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.post(
            "/admin/meetings/m_01:start-processing",
            json={"options": {"enableAsr": True}},
            headers=auth_headers,
        )
    assert response.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    create_call = next(c for c in stub.received if c["path"].endswith("/processing-tasks") and c["method"] == "POST")
    assert create_call["body"]["holdAtWorkerPhase"] is True
    assert create_call["body"]["taskType"] == "MEETING_FULL_PIPELINE"
    assert create_call["tenant"] == "tenant_01"


@pytest.mark.asyncio
async def test_finalize_resolves_latest_task_then_calls_resume(app: FastAPI, auth_headers: dict[str, str]):
    async with _client(app) as client:
        response = await client.post("/admin/meetings/m_01:finalize", headers=auth_headers)
    assert response.status_code == 200
    stub: _StubJavaClient = app.state.java_stub
    paths = [c["path"] for c in stub.received]
    assert "/api/meetings/m_01/processing-tasks/latest" in paths
    assert "/api/processing-tasks/task_01:resume-java-phase" in paths


@pytest.mark.asyncio
async def test_missing_jwt_returns_401(app: FastAPI):
    async with _client(app) as client:
        response = await client.post("/admin/meetings/m_01:finalize")
    assert response.status_code == 401
    body = response.json()
    assert body["detail"]["code"] == "UNAUTHENTICATED"


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
