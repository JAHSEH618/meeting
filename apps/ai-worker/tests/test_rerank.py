import json
import hashlib
import hmac
from datetime import datetime, timezone

from fastapi.testclient import TestClient

from ai_worker.common.config import settings
from ai_worker.interfaces.api.main import create_app


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
    nonce = "test_nonce_12345"
    signature = _sign(method, path, body, timestamp, nonce)
    return {
        "X-Request-Id": "req_test",
        "X-Trace-Id": "trace_test",
        "X-Tenant-Id": "tenant_01",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
    }


def test_rerank_endpoint_returns_stable_rank() -> None:
    client = TestClient(create_app())
    body = json.dumps({
        "tenantId": "tenant_01",
        "query": "什么是一期范围？",
        "candidates": [
            {"chunkId": "chunk_01", "sourceType": "DOCUMENT", "text": "一期范围包括...", "rrfScore": 0.9},
            {"chunkId": "chunk_02", "sourceType": "PRIMARY_TRANSCRIPT", "text": "会议纪要...", "rrfScore": 0.7},
            {"chunkId": "chunk_03", "sourceType": "AI_SUMMARY", "text": "总结...", "rrfScore": 0.5},
        ],
        "topN": 2,
        "modelVersion": "test-v0",
    }).encode()
    headers = _auth_headers("POST", "/internal/rerank", body)
    headers["Content-Type"] = "application/json"

    response = client.post("/internal/rerank", content=body, headers=headers)
    assert response.status_code == 200
    data = response.json()
    assert data["success"] is True
    assert data["data"]["modelVersion"] == "test-v0"
    assert len(data["data"]["items"]) == 2
    assert data["data"]["items"][0]["rank"] == 1
    assert data["data"]["items"][0]["rerankScore"] >= data["data"]["items"][1]["rerankScore"]


def test_rerank_endpoint_rejects_missing_signature() -> None:
    client = TestClient(create_app())
    body = json.dumps({
        "tenantId": "tenant_01",
        "query": "test",
        "candidates": [{"chunkId": "c1", "sourceType": "PRIMARY_TRANSCRIPT", "text": "hello", "rrfScore": 0.5}],
        "topN": 1,
        "modelVersion": "v0",
    }).encode()

    response = client.post("/internal/rerank", content=body, headers={"Content-Type": "application/json"})
    assert response.status_code == 422


def test_rerank_endpoint_rejects_invalid_signature() -> None:
    client = TestClient(create_app())
    body = json.dumps({
        "tenantId": "tenant_01",
        "query": "test",
        "candidates": [{"chunkId": "c1", "sourceType": "PRIMARY_TRANSCRIPT", "text": "hello", "rrfScore": 0.5}],
        "topN": 1,
        "modelVersion": "v0",
    }).encode()
    headers = _auth_headers("POST", "/internal/rerank", body)
    headers["X-Signature"] = "hmac-sha256=0000000000000000000000000000000000000000000000000000000000000000"
    headers["Content-Type"] = "application/json"

    response = client.post("/internal/rerank", content=body, headers=headers)
    assert response.status_code == 401
    data = response.json()
    assert data["success"] is False
    assert data["error"]["code"] == "RERANK_AUTH_FAILED"


def test_rerank_endpoint_rejects_empty_query() -> None:
    client = TestClient(create_app())
    body = json.dumps({
        "tenantId": "tenant_01",
        "query": "",
        "candidates": [{"chunkId": "c1", "sourceType": "PRIMARY_TRANSCRIPT", "text": "hello", "rrfScore": 0.5}],
        "topN": 1,
        "modelVersion": "v0",
    }).encode()
    headers = _auth_headers("POST", "/internal/rerank", body)
    headers["Content-Type"] = "application/json"

    response = client.post("/internal/rerank", content=body, headers=headers)
    assert response.status_code == 400
    data = response.json()
    assert data["success"] is False
    assert data["error"]["code"] == "RERANK_CONTRACT_ERROR"


def test_rerank_endpoint_rejects_empty_candidates() -> None:
    client = TestClient(create_app())
    body = json.dumps({
        "tenantId": "tenant_01",
        "query": "test query",
        "candidates": [],
        "topN": 1,
        "modelVersion": "v0",
    }).encode()
    headers = _auth_headers("POST", "/internal/rerank", body)
    headers["Content-Type"] = "application/json"

    response = client.post("/internal/rerank", content=body, headers=headers)
    assert response.status_code == 400
    data = response.json()
    assert data["success"] is False
    assert data["error"]["code"] == "RERANK_CONTRACT_ERROR"


def test_rerank_endpoint_truncates_to_top_n() -> None:
    client = TestClient(create_app())
    candidates = [
        {"chunkId": f"chunk_{i:02d}", "sourceType": "DOCUMENT", "text": f"text_{i}", "rrfScore": 1.0 - i * 0.1}
        for i in range(10)
    ]
    body = json.dumps({
        "tenantId": "tenant_01",
        "query": "test query",
        "candidates": candidates,
        "topN": 3,
        "modelVersion": "test-v0",
    }).encode()
    headers = _auth_headers("POST", "/internal/rerank", body)
    headers["Content-Type"] = "application/json"

    response = client.post("/internal/rerank", content=body, headers=headers)
    assert response.status_code == 200
    data = response.json()
    assert len(data["data"]["items"]) == 3