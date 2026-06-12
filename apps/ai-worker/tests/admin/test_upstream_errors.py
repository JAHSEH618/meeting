from __future__ import annotations

import httpx
import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

from ai_worker.admin.java_client import JavaPublicClient, UpstreamUnavailableError
from ai_worker.admin.router import build_admin_router, register_admin_exception_handlers
from ._jwt_helpers import make_admin_token


class _DownJavaClient(JavaPublicClient):
    def __init__(self) -> None:
        self._base_url = "http://meeting-api.test"
        self._timeout = 5.0

    async def request(self, method, path, **kwargs):  # type: ignore[override]
        raise UpstreamUnavailableError("meeting-api unavailable: connection refused")


def _app(java_client: JavaPublicClient) -> FastAPI:
    app = FastAPI()
    app.include_router(build_admin_router(java_client=java_client))
    register_admin_exception_handlers(app)
    return app


def _auth_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {make_admin_token()}"}


@pytest.mark.asyncio
async def test_upstream_unreachable_returns_502_envelope() -> None:
    app = _app(_DownJavaClient())
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.get("/admin/meetings", headers=_auth_headers())

    assert response.status_code == 502
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "UPSTREAM_UNAVAILABLE"
    assert body["error"]["retryable"] is True


@pytest.mark.asyncio
async def test_malformed_json_body_returns_400_envelope() -> None:
    app = _app(_DownJavaClient())
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://workstation") as client:
        response = await client.post(
            "/admin/meetings",
            headers={**_auth_headers(), "Content-Type": "application/json"},
            content=b"{not json",
        )

    assert response.status_code == 400
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_FAILED"


@pytest.mark.asyncio
async def test_httpx_request_error_is_wrapped_by_java_client(monkeypatch) -> None:
    import time
    client = JavaPublicClient(base_url="http://meeting-api.test")

    async def raise_connect_error(*args, **kwargs):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(client._client, "request", raise_connect_error)
    from ai_worker.admin.jwt_middleware import AdminClaims

    claims = AdminClaims(
        subject="u1",
        tenant_id="t1",
        roles=("ADMIN",),
        raw_token="tok",
        expires_at=int(time.time()) + 3600,
    )
    with pytest.raises(UpstreamUnavailableError):
        await client.request("GET", "/api/meetings", claims=claims)
