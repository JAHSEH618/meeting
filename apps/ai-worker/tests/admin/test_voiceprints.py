"""Admin BFF speaker-profile passthrough endpoints."""

from __future__ import annotations

import time
from unittest.mock import AsyncMock, MagicMock

import httpx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_worker.admin.enrollment import build_voiceprint_router
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
    app.include_router(build_voiceprint_router(java_client=client_mock))
    app.dependency_overrides[admin_claims_dependency] = _claims
    return TestClient(app)


def _resp(status: int, body: bytes = b'{"success":true,"data":{}}') -> httpx.Response:
    return httpx.Response(
        status_code=status,
        content=body,
        headers={"content-type": "application/json"},
    )


def test_list_voiceprints_uses_current_java_speaker_profile_endpoint() -> None:
    client = MagicMock()
    client.request = AsyncMock(
        return_value=_resp(
            200,
            b'{"success":true,"data":[{"speakerProfileId":"sp1","personId":"p1","displayName":"\xe6\x9d\x8e\xe5\x9b\x9b","consentStatus":"ACTIVE"}]}',
        )
    )

    response = _app(client).get(
        "/admin/voiceprints",
        headers={"X-Request-Id": "r1", "X-Trace-Id": "tr1"},
    )

    assert response.status_code == 200
    assert "sp1" in response.text
    client.request.assert_awaited_once()
    args = client.request.await_args
    assert args.args[:2] == ("GET", "/api/speaker-profiles")
    assert args.kwargs["request_id"] == "r1"
    assert args.kwargs["trace_id"] == "tr1"


def test_revoke_voiceprint_uses_current_java_profile_revoke_endpoint() -> None:
    client = MagicMock()
    client.request = AsyncMock(return_value=_resp(200, b'{"success":true,"data":null}'))

    response = _app(client).post(
        "/admin/voiceprints/sp1:revoke",
        json={"reason": "operator_request"},
        headers={
            "X-Request-Id": "r1",
            "X-Trace-Id": "tr1",
            "Idempotency-Key": "i1",
        },
    )

    assert response.status_code == 200
    client.request.assert_awaited_once()
    args = client.request.await_args
    assert args.args[:2] == ("POST", "/api/speaker-profiles/sp1/revoke")
    assert args.kwargs["json"] == {"reason": "operator_request"}
    assert args.kwargs["request_id"] == "r1"
    assert args.kwargs["trace_id"] == "tr1"
    assert args.kwargs["idempotency_key"] == "i1"
