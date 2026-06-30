"""Single source of truth for the internal-API / callback HMAC signing string.

The inbound verifier (``infrastructure/internal_api/auth.py``) and every outbound
signer (``infrastructure/java_callback/client.py``,
``infrastructure/speaker/reference_client.py``) MUST produce a byte-for-byte
identical canonical string, and it must match Java's CallbackSecurityVerifier /
InternalApiSignatureVerifier / HttpAiWorkerInternalClient:

    {timestamp}\\n{nonce}\\n{METHOD}\\n{path}\\n{sha256_hex(body)}

The HMAC is hex(SHA-256); the signature header value is ``hmac-sha256=<hex>``.
The timestamp string is generated per-caller (formats differ across callers) and
passed in verbatim — never re-serialize it here, or signer and verifier will
disagree (see the OffsetDateTime.toString() bug this exact rule prevents).
"""
from __future__ import annotations

import hashlib
import hmac

SIGNATURE_PREFIX = "hmac-sha256="


def signing_string(method: str, path: str, body: bytes, timestamp: str, nonce: str) -> str:
    """Build the canonical string that is HMAC'd. ``body`` is the raw request bytes."""
    return f"{timestamp}\n{nonce}\n{method}\n{path}\n{hashlib.sha256(body).hexdigest()}"


def compute_signature(
    secret: str, method: str, path: str, body: bytes, timestamp: str, nonce: str
) -> str:
    """Return the ``hmac-sha256=<hex>`` signature header value for the request."""
    mac = hmac.new(
        secret.encode("utf-8"),
        signing_string(method, path, body, timestamp, nonce).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return f"{SIGNATURE_PREFIX}{mac}"
