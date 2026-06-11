"""Admin BFF persons passthrough endpoints."""

from __future__ import annotations

import time
from unittest.mock import AsyncMock, MagicMock

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_worker.admin.jwt_middleware import AdminClaims, admin_claims_dependency
from ai_worker.admin.persons import build_persons_router


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
    app.include_router(build_persons_router(java_client=client_mock))
    app.dependency_overrides[admin_claims_dependency] = _claims
    return TestClient(app)


def _resp(status: int, body: bytes = b'{"success":true,"data":{}}') -> httpx.Response:
    return httpx.Response(
        status_code=status,
        content=body,
        headers={"content-type": "application/json"},
    )


def test_search_passthrough() -> None:
    client = MagicMock()
    client.request = AsyncMock(
        return_value=_resp(200, b'{"success":true,"data":[{"personId":"p1","displayName":"\xe6\x9d\x8e\xe5\x9b\x9b"}]}')
    )

    response = _app(client).get(
        "/admin/persons?q=lis",
        headers={"X-Request-Id": "r1", "X-Trace-Id": "tr1"},
    )

    assert response.status_code == 200
    assert "李四" in response.text
    client.request.assert_awaited_once()
    args = client.request.await_args
    assert args.args[:2] == ("GET", "/api/persons")
    assert args.kwargs["params"] == {"q": "lis"}
    assert args.kwargs["request_id"] == "r1"
    assert args.kwargs["trace_id"] == "tr1"


def test_create_passthrough_happy() -> None:
    client = MagicMock()
    client.request = AsyncMock(
        return_value=_resp(200, b'{"success":true,"data":{"personId":"p2","displayName":"\xe5\xbc\xa0\xe4\xb8\x89"}}')
    )

    response = _app(client).post(
        "/admin/persons",
        json={"displayName": "张三"},
        headers={
            "X-Request-Id": "r1",
            "X-Trace-Id": "tr1",
            "Idempotency-Key": "i1",
        },
    )

    assert response.status_code == 200
    args = client.request.await_args
    assert args.args[:2] == ("POST", "/api/persons")
    assert args.kwargs["json"] == {"displayName": "张三"}
    assert args.kwargs["idempotency_key"] == "i1"


def test_create_409_duplicate_passthrough() -> None:
    client = MagicMock()
    client.request = AsyncMock(
        return_value=_resp(
            409,
            b'{"success":false,"data":null,"error":{"code":"PERSON_DUPLICATE","message":"dup","retryable":false,"details":{"matches":[{"personId":"p1","displayName":"\xe5\xbc\xa0\xe4\xb8\x89"}]}}}',
        )
    )

    response = _app(client).post(
        "/admin/persons",
        json={"displayName": "张三"},
        headers={
            "X-Request-Id": "r1",
            "X-Trace-Id": "tr1",
            "Idempotency-Key": "i1",
        },
    )

    assert response.status_code == 409
    assert "PERSON_DUPLICATE" in response.text
