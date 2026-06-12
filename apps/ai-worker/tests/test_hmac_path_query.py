import hashlib
import hmac
import secrets
from datetime import datetime, timezone

from fastapi.testclient import TestClient

from ai_worker.common.config import settings
from ai_worker.interfaces.api.main import create_app


def _sign(method: str, path_with_query: str, body: bytes, timestamp: str, nonce: str) -> str:
    signing_string = (
        f"{timestamp}\n{nonce}\n{method}\n{path_with_query}\n{hashlib.sha256(body).hexdigest()}"
    )
    sig = hmac.new(
        settings.internal_api_hmac_secret.encode(), signing_string.encode(), hashlib.sha256
    ).hexdigest()
    return f"hmac-sha256={sig}"


def _headers(method: str, signed_path: str, body: bytes) -> dict[str, str]:
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    nonce = f"pathquery_{secrets.token_hex(8)}"
    return {
        "X-Request-Id": "req_pq",
        "X-Trace-Id": "trace_pq",
        "X-Tenant-Id": "tenant_01",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": _sign(method, signed_path, body, timestamp, nonce),
    }


def test_warmup_accepts_signature_over_path_with_query() -> None:
    client = TestClient(create_app())
    response = client.post(
        "/internal/models/warmup?capabilities=embedding",
        content=b"",
        headers=_headers("POST", "/internal/models/warmup?capabilities=embedding", b""),
    )
    assert response.status_code == 200


def test_warmup_rejects_signature_that_omits_the_query_string() -> None:
    client = TestClient(create_app())
    # Signature computed over the bare path — a forged/stripped query must fail.
    response = client.post(
        "/internal/models/warmup?capabilities=embedding",
        content=b"",
        headers=_headers("POST", "/internal/models/warmup", b""),
    )
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "MODELS_AUTH_FAILED"
