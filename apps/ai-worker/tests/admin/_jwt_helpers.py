"""Helpers shared across admin tests."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any

from ai_worker.common.config import settings


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def make_admin_token(
    *,
    sub: str = "user_01",
    tenant_id: str = "tenant_01",
    roles: list[str] | None = None,
    aud: str | None = None,
    iss: str | None = None,
    exp_offset_seconds: int = 3600,
    alg: str = "HS256",
    secret: str | None = None,
) -> str:
    """Mint an HS256 JWT compatible with ai_worker.admin.jwt_middleware."""
    header = {"alg": alg, "typ": "JWT"}
    payload: dict[str, Any] = {
        "sub": sub,
        "tenantId": tenant_id,
        "roles": roles or ["ADMIN"],
        "aud": aud or settings.admin_jwt_audience,
        "iss": iss or settings.admin_jwt_issuer,
        "exp": int(time.time()) + exp_offset_seconds,
        "iat": int(time.time()),
    }
    header_b64 = b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    payload_b64 = b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{header_b64}.{payload_b64}".encode("utf-8")
    use_secret = secret if secret is not None else settings.admin_jwt_secret
    signature = hmac.new(use_secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{header_b64}.{payload_b64}.{b64url(signature)}"
