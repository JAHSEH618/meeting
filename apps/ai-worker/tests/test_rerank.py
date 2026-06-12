import json
import hashlib
import hmac
import secrets
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


_nonce_counter = 0

def _auth_headers(method: str, path: str, body: bytes) -> dict[str, str]:
    global _nonce_counter
    _nonce_counter += 1
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    nonce = f"test_nonce_{_nonce_counter}_{secrets.token_hex(4)}"
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


class _ScriptedRerankRuntime:
    """Real-mode style runtime returning non-monotonic scores (D6)."""

    model_version = "scripted-rerank-v1"
    status = "READY"
    last_error = None
    device = "fake"
    use_fake = True

    async def ensure_loaded(self) -> None:
        return None

    async def arank(self, query: str, texts: list[str]) -> list[float]:
        scores = {"low text": 0.1, "high text": 0.9, "mid text": 0.5}
        return [scores[t] for t in texts]


def test_rerank_scores_all_candidates_before_topn_slice(monkeypatch) -> None:
    from ai_worker.interfaces.api import main as api_main

    monkeypatch.setattr(api_main, "get_bge_reranker", lambda: _ScriptedRerankRuntime())
    client = TestClient(api_main.create_app())
    body = json.dumps({
        "tenantId": "tenant_01",
        "query": "ordering",
        "candidates": [
            {"chunkId": "chunk_01", "sourceType": "DOCUMENT", "text": "low text", "rrfScore": 0.9},
            {"chunkId": "chunk_02", "sourceType": "PRIMARY_TRANSCRIPT", "text": "high text", "rrfScore": 0.7},
            {"chunkId": "chunk_03", "sourceType": "AI_SUMMARY", "text": "mid text", "rrfScore": 0.5},
        ],
        "topN": 2,
        "modelVersion": "test-v0",
    }).encode()
    headers = _auth_headers("POST", "/internal/rerank", body)
    headers["Content-Type"] = "application/json"

    response = client.post("/internal/rerank", content=body, headers=headers)

    assert response.status_code == 200
    items = response.json()["data"]["items"]
    # chunk_03 (0.5) must beat chunk_01 (0.1) — pre-truncation would have
    # dropped chunk_03 entirely.
    assert [(i["chunkId"], i["rank"]) for i in items] == [("chunk_02", 1), ("chunk_03", 2)]