"""Minimal HS256 JWT validator for the workstation admin UI.

Tokens are minted by meeting-api at /auth/login and forwarded by the SPA
in the ``Authorization: Bearer ...`` header. We verify:
  - structure (header.payload.signature, base64url)
  - alg = HS256
  - HMAC signature against ``settings.admin_jwt_secret``
  - exp not expired
  - aud == ``settings.admin_jwt_audience``
  - iss == ``settings.admin_jwt_issuer``
  - role in payload contains ``settings.admin_jwt_required_role``

We deliberately do NOT depend on PyJWT — the workstation runs in the same
process as the AI worker which is already on a tight dep budget. A JWKS /
RS256 migration is tracked separately and would slot in behind the same
``AdminClaims`` interface.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass
from typing import Any

from fastapi import Header, HTTPException, status
from starlette.responses import JSONResponse

from ai_worker.common.config import settings


@dataclass(frozen=True)
class AdminClaims:
    subject: str
    tenant_id: str
    roles: tuple[str, ...]
    raw_token: str
    expires_at: int


class JwtValidationError(Exception):
    """Raised when a token fails any structural / signature / claim check."""


def _b64url_decode(data: str) -> bytes:
    padding = "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data + padding)


def _verify_signature(header_b64: str, payload_b64: str, signature_b64: str) -> bool:
    expected = hmac.new(
        settings.admin_jwt_secret.encode("utf-8"),
        f"{header_b64}.{payload_b64}".encode("utf-8"),
        hashlib.sha256,
    ).digest()
    try:
        provided = _b64url_decode(signature_b64)
    except Exception as exc:  # malformed base64
        raise JwtValidationError(f"malformed signature: {exc}")
    return hmac.compare_digest(expected, provided)


def decode_admin_token(token: str, *, now: int | None = None) -> AdminClaims:
    """Strict JWT decode + validate.

    Raises :class:`JwtValidationError` on any failure with a human-readable
    reason; callers translate that to a 401 envelope.
    """
    if not token or token.count(".") != 2:
        raise JwtValidationError("token must have three segments")
    header_b64, payload_b64, signature_b64 = token.split(".", 2)

    try:
        header: dict[str, Any] = json.loads(_b64url_decode(header_b64))
        payload: dict[str, Any] = json.loads(_b64url_decode(payload_b64))
    except Exception as exc:
        raise JwtValidationError(f"malformed JWT segments: {exc}")

    alg = header.get("alg")
    if alg != "HS256":
        raise JwtValidationError(f"unsupported alg: {alg}")

    if not _verify_signature(header_b64, payload_b64, signature_b64):
        raise JwtValidationError("signature mismatch")

    expected_aud = settings.admin_jwt_audience
    aud = payload.get("aud")
    if aud != expected_aud and not (isinstance(aud, list) and expected_aud in aud):
        raise JwtValidationError(f"audience mismatch: aud={aud!r}")

    expected_iss = settings.admin_jwt_issuer
    if payload.get("iss") != expected_iss:
        raise JwtValidationError(f"issuer mismatch: iss={payload.get('iss')!r}")

    exp = payload.get("exp")
    if not isinstance(exp, int):
        raise JwtValidationError("exp claim missing or not an int")
    current = int(time.time()) if now is None else now
    if current >= exp:
        raise JwtValidationError("token expired")

    roles_value = payload.get("roles") or []
    if isinstance(roles_value, str):
        roles_tuple: tuple[str, ...] = (roles_value,)
    elif isinstance(roles_value, list):
        roles_tuple = tuple(str(r) for r in roles_value)
    else:
        raise JwtValidationError("roles claim must be a string or list")

    required = settings.admin_jwt_required_role
    if required and required not in roles_tuple:
        raise JwtValidationError(f"missing required role: {required}")

    subject = payload.get("sub")
    tenant_id = payload.get("tenantId") or payload.get("tenant_id")
    if not isinstance(subject, str) or not subject:
        raise JwtValidationError("sub claim missing")
    if not isinstance(tenant_id, str) or not tenant_id:
        raise JwtValidationError("tenantId claim missing")

    return AdminClaims(
        subject=subject,
        tenant_id=tenant_id,
        roles=roles_tuple,
        raw_token=token,
        expires_at=exp,
    )


def admin_claims_dependency(
    authorization: str | None = Header(None, alias="Authorization"),
    x_request_id: str | None = Header(None, alias="X-Request-Id"),
    x_trace_id: str | None = Header(None, alias="X-Trace-Id"),
) -> AdminClaims:
    """FastAPI dependency: parses Authorization, returns validated claims."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={
                "code": "UNAUTHENTICATED",
                "message": "missing or malformed Authorization header",
                "retryable": False,
                "requestId": x_request_id or "",
                "traceId": x_trace_id or "",
            },
        )
    token = authorization.split(" ", 1)[1].strip()
    try:
        return decode_admin_token(token)
    except JwtValidationError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={
                "code": "UNAUTHENTICATED",
                "message": str(exc),
                "retryable": False,
                "requestId": x_request_id or "",
                "traceId": x_trace_id or "",
            },
        )


def admin_unauthenticated_response(message: str, request_id: str, trace_id: str) -> JSONResponse:
    """Helper for routes that want to emit the canonical 401 envelope inline."""
    return JSONResponse(
        status_code=401,
        content={
            "success": False,
            "data": None,
            "error": {
                "code": "UNAUTHENTICATED",
                "message": message,
                "retryable": False,
            },
            "requestId": request_id,
            "traceId": trace_id,
        },
    )
