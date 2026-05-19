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

from ai_worker.admin.jwt_middleware import JwtValidationError, decode_admin_token
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


def test_non_hs256_alg_is_rejected():
    # Crafted token with alg=none — sometimes used in CVE-style attacks.
    token = make_admin_token(alg="none")

    with pytest.raises(JwtValidationError, match="unsupported alg"):
        decode_admin_token(token)
