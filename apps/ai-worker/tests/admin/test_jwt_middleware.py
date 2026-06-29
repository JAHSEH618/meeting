"""C5.1 — JWT validation middleware tests.

Covers todo-final.md:
* valid token returns claims with tenantId / sub / roles populated
* expired token rejected
* wrong audience rejected
* missing required role rejected
* tampered signature rejected
"""

from __future__ import annotations

import pytest
import httpx

from ai_worker.admin import jwt_middleware
from ai_worker.admin.jwt_middleware import (
    JwtValidationError,
    admin_claims_dependency,
    decode_admin_token,
)
from ai_worker.common.config import settings
from ._jwt_helpers import make_admin_token


def test_valid_admin_token_returns_claims():
    token = make_admin_token(sub="user_01", tenant_id="tenant_01", roles=["ADMIN", "AUDITOR"])

    claims = decode_admin_token(token)

    assert claims.subject == "user_01"
    assert claims.tenant_id == "tenant_01"
    assert "ADMIN" in claims.roles
    assert claims.raw_token == token


def test_expired_token_is_rejected():
    token = make_admin_token(exp_offset_seconds=-60)

    with pytest.raises(JwtValidationError, match="expired"):
        decode_admin_token(token)


def test_wrong_audience_is_rejected():
    token = make_admin_token(aud="some-other-app")

    with pytest.raises(JwtValidationError, match="audience"):
        decode_admin_token(token)


def test_missing_required_role_is_rejected():
    token = make_admin_token(roles=["VIEWER"])

    with pytest.raises(JwtValidationError, match="role"):
        decode_admin_token(token)


def test_tampered_signature_is_rejected():
    token = make_admin_token()
    header, payload, _ = token.split(".")
    forged = f"{header}.{payload}.AAAA"  # garbage signature

    with pytest.raises(JwtValidationError, match="signature"):
        decode_admin_token(forged)


def test_wrong_issuer_is_rejected():
    token = make_admin_token(iss="someone-else")

    with pytest.raises(JwtValidationError, match="issuer"):
        decode_admin_token(token)


def test_not_yet_valid_nbf_token_is_rejected():
    # nbf well beyond the clock-skew leeway → not yet valid.
    token = make_admin_token(nbf_offset_seconds=3600)

    with pytest.raises(JwtValidationError, match="not yet valid"):
        decode_admin_token(token)


def test_lowercase_role_satisfies_required_role():
    # Role comparison is case-insensitive and consistent across paths.
    token = make_admin_token(roles=["admin"])

    claims = decode_admin_token(token)

    assert claims.tenant_id == "tenant_01"


def test_non_hs256_alg_is_rejected():
    # Crafted token with alg=none — sometimes used in CVE-style attacks.
    token = make_admin_token(alg="none")

    with pytest.raises(JwtValidationError, match="unsupported alg"):
        decode_admin_token(token)


def test_legacy_mvp_session_token_is_verified_by_java_me(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seen: dict[str, object] = {}

    class FakeClient:
        def __init__(self, *, base_url: str, timeout: float) -> None:
            seen["base_url"] = base_url
            seen["timeout"] = timeout

        def __enter__(self) -> "FakeClient":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def get(self, path: str, *, headers: dict[str, str]) -> httpx.Response:
            seen["path"] = path
            seen["headers"] = headers
            return httpx.Response(
                200,
                json={
                    "success": True,
                    "data": {
                        "userId": "user_admin",
                        "tenantId": "tenant_default",
                        "roles": ["admin"],
                    },
                    "error": None,
                    "requestId": "r",
                    "traceId": "t",
                },
            )

    monkeypatch.setattr(settings, "java_api_base_url", "http://10.9.50.179:8080")
    monkeypatch.setattr(jwt_middleware.httpx, "Client", FakeClient)

    claims = admin_claims_dependency(
        "Bearer mvp0_legacy",
        x_request_id="r",
        x_trace_id="t",
    )

    assert claims.subject == "user_admin"
    assert claims.tenant_id == "tenant_default"
    assert claims.raw_token == "mvp0_legacy"
    assert claims.roles == ("admin",)
    assert seen["base_url"] == "http://10.9.50.179:8080"
    assert seen["path"] == "/api/auth/me"
    assert seen["headers"] == {
        "Authorization": "Bearer mvp0_legacy",
        "X-Request-Id": "r",
        "X-Trace-Id": "t",
    }
