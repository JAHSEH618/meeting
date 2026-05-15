from __future__ import annotations

import hashlib
import hmac
import json
import secrets
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from ai_worker.common.config import settings
from ai_worker.interfaces.api.main import create_app
from ai_worker.model_runtime import registry


def _sign(method: str, path: str, body: bytes, timestamp: str, nonce: str) -> str:
    signing_string = f"{timestamp}\n{nonce}\n{method}\n{path}\n{hashlib.sha256(body).hexdigest()}"
    sig = hmac.new(
        settings.internal_api_hmac_secret.encode(),
        signing_string.encode(),
        hashlib.sha256,
    ).hexdigest()
    return f"hmac-sha256={sig}"


def _auth_headers(method: str, path: str, body: bytes) -> dict[str, str]:
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    nonce = f"embed_test_{secrets.token_hex(4)}"
    signature = _sign(method, path, body, timestamp, nonce)
    return {
        "X-Request-Id": "req_test_embed",
        "X-Trace-Id": "trace_test_embed",
        "X-Tenant-Id": "tenant_01",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
        "Content-Type": "application/json",
    }


@pytest.fixture(autouse=True)
def _reset_registry() -> None:
    registry.reset_for_tests()
    yield
    registry.reset_for_tests()


def test_embed_returns_1024_dim_vector_for_single_text() -> None:
    client = TestClient(create_app())
    body = json.dumps(
        {
            "tenantId": "tenant_01",
            "texts": ["What decisions were made about the Q3 budget?"],
            "modelVersion": "bge-m3-v1",
        }
    ).encode()
    headers = _auth_headers("POST", "/internal/embed", body)

    response = client.post("/internal/embed", content=body, headers=headers)

    assert response.status_code == 200
    payload = response.json()
    assert payload["success"] is True
    data = payload["data"]
    assert data["modelVersion"] == "bge-m3-fake-v0"  # fake-mode reports fake version
    assert data["dimension"] == 1024
    assert len(data["vectors"]) == 1
    assert len(data["vectors"][0]) == 1024


def test_embed_preserves_order_for_batched_inputs() -> None:
    client = TestClient(create_app())
    texts = [f"chunk text {i}" for i in range(8)]
    body = json.dumps(
        {
            "tenantId": "tenant_01",
            "texts": texts,
            "modelVersion": "bge-m3-v1",
        }
    ).encode()
    headers = _auth_headers("POST", "/internal/embed", body)

    response = client.post("/internal/embed", content=body, headers=headers)

    assert response.status_code == 200
    vectors = response.json()["data"]["vectors"]
    assert len(vectors) == 8

    # Re-embed item index 5 alone and confirm it matches the batch position.
    single_body = json.dumps(
        {"tenantId": "tenant_01", "texts": [texts[5]], "modelVersion": "bge-m3-v1"}
    ).encode()
    single_headers = _auth_headers("POST", "/internal/embed", single_body)
    single = client.post(
        "/internal/embed", content=single_body, headers=single_headers
    )
    assert single.json()["data"]["vectors"][0] == vectors[5]


def test_embed_rejects_missing_signature() -> None:
    client = TestClient(create_app())
    body = json.dumps(
        {"tenantId": "tenant_01", "texts": ["q"], "modelVersion": "v1"}
    ).encode()
    response = client.post(
        "/internal/embed", content=body, headers={"Content-Type": "application/json"}
    )
    assert response.status_code == 422  # FastAPI required header validation


def test_embed_rejects_invalid_signature() -> None:
    client = TestClient(create_app())
    body = json.dumps(
        {"tenantId": "tenant_01", "texts": ["q"], "modelVersion": "v1"}
    ).encode()
    headers = _auth_headers("POST", "/internal/embed", body)
    headers["X-Signature"] = "hmac-sha256=" + "0" * 64

    response = client.post("/internal/embed", content=body, headers=headers)

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "EMBEDDING_AUTH_FAILED"


def test_embed_rejects_empty_texts() -> None:
    client = TestClient(create_app())
    body = json.dumps(
        {"tenantId": "tenant_01", "texts": [], "modelVersion": "v1"}
    ).encode()
    headers = _auth_headers("POST", "/internal/embed", body)

    response = client.post("/internal/embed", content=body, headers=headers)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "EMBEDDING_CONTRACT_ERROR"


def test_embed_rejects_too_many_texts() -> None:
    client = TestClient(create_app())
    body = json.dumps(
        {
            "tenantId": "tenant_01",
            "texts": [f"t{i}" for i in range(65)],
            "modelVersion": "v1",
        }
    ).encode()
    headers = _auth_headers("POST", "/internal/embed", body)

    response = client.post("/internal/embed", content=body, headers=headers)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "EMBEDDING_CONTRACT_ERROR"


def test_embed_rejects_blank_text() -> None:
    client = TestClient(create_app())
    body = json.dumps(
        {"tenantId": "tenant_01", "texts": [""], "modelVersion": "v1"}
    ).encode()
    headers = _auth_headers("POST", "/internal/embed", body)

    response = client.post("/internal/embed", content=body, headers=headers)

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "EMBEDDING_CONTRACT_ERROR"


def test_embed_returns_503_when_real_runtime_load_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "use_fake_runtime", False)
    registry.reset_for_tests()

    bge_m3 = registry.get_bge_m3()

    def _boom() -> None:
        raise RuntimeError("simulated weights missing")

    monkeypatch.setattr(bge_m3, "_load_model_blocking", _boom)

    client = TestClient(create_app())
    body = json.dumps(
        {"tenantId": "tenant_01", "texts": ["q"], "modelVersion": "v1"}
    ).encode()
    headers = _auth_headers("POST", "/internal/embed", body)

    response = client.post("/internal/embed", content=body, headers=headers)

    assert response.status_code == 503
    payload = response.json()
    assert payload["success"] is False
    assert payload["error"]["code"] == "EMBEDDING_UNAVAILABLE"
    assert payload["error"]["retryable"] is True
