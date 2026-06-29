"""Minimal HS256 JWT validator for the workstation admin UI.

Tokens are minted by meeting-api at /api/auth/login and forwarded by the SPA
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

During the remote-Java transition we also accept legacy ``mvp0_*`` dev
session tokens by asking meeting-api ``/api/auth/me`` to verify them. The
BFF still forwards the original token to Java, so existing CentOS services
keep working until they are redeployed with HS256 admin JWT support.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass
from typing import Any

import httpx
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


# Small allowance for clock skew between meeting-api (token minter) and the
# worker when checking the not-before claim.
_CLOCK_SKEW_LEEWAY_SECONDS = 60


class JwtValidationError(Exception):
    """Raised when a token fails any structural / signature / claim check."""


def _has_required_role(required: str, roles: tuple[str, ...]) -> bool:
    """Case-insensitive role check shared by the primary and legacy paths.

    Previously the primary path compared case-sensitively while the legacy path
    lower-cased, so the same role string could pass one path and fail the other.
    """
    if not required:
        return True
    required_lower = required.lower()
    return any(role.lower() == required_lower for role in roles)


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

    nbf = payload.get("nbf")
    if nbf is not None:
        if not isinstance(nbf, int):
            raise JwtValidationError("nbf claim must be an int")
        if current + _CLOCK_SKEW_LEEWAY_SECONDS < nbf:
            raise JwtValidationError("token not yet valid (nbf)")

    roles_value = payload.get("roles") or []
    if isinstance(roles_value, str):
        roles_tuple: tuple[str, ...] = (roles_value,)
    elif isinstance(roles_value, list):
        roles_tuple = tuple(str(r) for r in roles_value)
    else:
        raise JwtValidationError("roles claim must be a string or list")

    required = settings.admin_jwt_required_role
    if not _has_required_role(required, roles_tuple):
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


def _decode_legacy_java_session(
    token: str,
    *,
    request_id: str,
    trace_id: str,
) -> AdminClaims:
    if not token.startswith("mvp0_"):
        raise JwtValidationError("not a legacy meeting-api session token")
    if not settings.java_api_base_url:
        raise JwtValidationError("java_api_base_url is not configured")

    headers = {"Authorization": f"Bearer {token}"}
    if request_id:
        headers["X-Request-Id"] = request_id
    if trace_id:
        headers["X-Trace-Id"] = trace_id
    try:
        with httpx.Client(
            base_url=settings.java_api_base_url.rstrip("/"),
            timeout=5.0,
        ) as client:
            response = client.get("/api/auth/me", headers=headers)
    except httpx.RequestError as exc:
        raise JwtValidationError(f"legacy token verification unavailable: {exc}") from exc
    if response.status_code == 401:
        raise JwtValidationError("legacy token rejected by meeting-api")
    try:
        envelope = response.json()
    except Exception as exc:
        raise JwtValidationError(f"legacy token verification returned non-JSON: {exc}") from exc
    if not response.is_success or envelope.get("success") is False or envelope.get("error"):
        message = (envelope.get("error") or {}).get("message") or f"HTTP {response.status_code}"
        raise JwtValidationError(f"legacy token rejected by meeting-api: {message}")

    user = envelope.get("data") or {}
    subject = user.get("userId") or user.get("subject")
    tenant_id = user.get("tenantId") or user.get("tenant_id")
    roles_value = user.get("roles") or []
    if isinstance(roles_value, str):
        roles_tuple = (roles_value,)
    elif isinstance(roles_value, list):
        roles_tuple = tuple(str(role) for role in roles_value)
    else:
        roles_tuple = ()

    required = settings.admin_jwt_required_role
    if not _has_required_role(required, roles_tuple):
        raise JwtValidationError(f"missing required role: {required}")
    if not isinstance(subject, str) or not subject:
        raise JwtValidationError("legacy token userId missing")
    if not isinstance(tenant_id, str) or not tenant_id:
        raise JwtValidationError("legacy token tenantId missing")

    return AdminClaims(
        subject=subject,
        tenant_id=tenant_id,
        roles=roles_tuple,
        raw_token=token,
        expires_at=int(time.time()) + settings.admin_session_ttl_seconds,
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
        # Only the explicit legacy session shape triggers the outbound
        # /api/auth/me verification. A malformed/forged HS256 token is terminal
        # 401 — it must not fall through to the legacy network path.
        if token.startswith("mvp0_"):
            try:
                return _decode_legacy_java_session(
                    token,
                    request_id=x_request_id or "",
                    trace_id=x_trace_id or "",
                )
            except JwtValidationError:
                pass
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
