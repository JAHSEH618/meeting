"""Admin BFF generic file upload passthrough endpoints."""

from __future__ import annotations

import time
from unittest.mock import AsyncMock, MagicMock

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_worker.admin.files import build_files_router
from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency


def _claims() -> AdminClaims:
    return AdminClaims(
        subject="u1",
        tenant_id="t1",
        roles=("ADMIN",),
        raw_token="token",
        expires_at=int(time.time()) + 3600,
    )


def _app(client_mock: MagicMock) -> TestClient:
    app = FastAPI()
    app.include_router(build_files_router(java_client=client_mock))
    app.dependency_overrides[admin_claims_dependency] = _claims
    return TestClient(app)


def _resp(status: int = 200, body: bytes = b'{"success":true,"data":{}}') -> httpx.Response:
    return httpx.Response(
        status_code=status,
        content=body,
        headers={"content-type": "application/json"},
    )


def _headers() -> dict[str, str]:
    return {
        "X-Request-Id": "r1",
        "X-Trace-Id": "t1",
        "Idempotency-Key": "i1",
    }


def test_init_upload_passthrough() -> None:
    client = MagicMock()
    client.request = AsyncMock(
        return_value=_resp(200, b'{"success":true,"data":{"uploadId":"u1","parts":[]}}')
    )

    response = _app(client).post(
        "/admin/files/uploads",
        json={
            "fileName": "ref.pdf",
            "contentType": "application/pdf",
            "fileSizeBytes": 4,
            "fileSha256": "a" * 64,
        },
        headers=_headers(),
    )

    assert response.status_code == 200
    args = client.request.await_args
    assert args.args[:2] == ("POST", "/api/files")
    assert args.kwargs["idempotency_key"] == "i1"


def test_part_passthrough() -> None:
    client = MagicMock()
    client.request = AsyncMock(return_value=_resp())

    response = _app(client).post(
        "/admin/files/uploads/u1/parts",
        json={"partNumber": 1, "sizeBytes": 4, "partSha256": "b" * 64},
        headers=_headers(),
    )

    assert response.status_code == 200
    assert client.request.await_args.args[:2] == ("POST", "/api/files/u1/parts")


def test_complete_passthrough() -> None:
    client = MagicMock()
    client.request = AsyncMock(
        return_value=_resp(
            200,
            b'{"success":true,"data":{"fileId":"f1","sha256":"x","sizeBytes":4,"contentType":"application/pdf"}}',
        )
    )

    response = _app(client).post(
        "/admin/files/uploads/u1/complete",
        json={
            "fileSha256": "c" * 64,
            "parts": [{"partNumber": 1, "partSha256": "b" * 64, "etag": "e"}],
        },
        headers=_headers(),
    )

    assert response.status_code == 200
    assert "fileId" in response.text
    assert client.request.await_args.args[:2] == ("POST", "/api/files/u1/complete")


def test_abort_passthrough() -> None:
    client = MagicMock()
    client.request = AsyncMock(return_value=_resp())

    response = _app(client).post("/admin/files/uploads/u1/abort", headers=_headers())

    assert response.status_code == 200
    assert client.request.await_args.args[:2] == ("POST", "/api/files/u1/abort")


def test_mime_not_allowed_passthrough() -> None:
    client = MagicMock()
    client.request = AsyncMock(
        return_value=_resp(
            415,
            b'{"success":false,"data":null,"error":{"code":"FILE_MIME_NOT_ALLOWED","message":"not allowed","retryable":false,"details":null}}',
        )
    )

    response = _app(client).post(
        "/admin/files/uploads",
        json={
            "fileName": "x.exe",
            "contentType": "application/x-msdownload",
            "fileSizeBytes": 1,
            "fileSha256": "z" * 64,
        },
        headers=_headers(),
    )

    assert response.status_code == 415
    assert "FILE_MIME_NOT_ALLOWED" in response.text
