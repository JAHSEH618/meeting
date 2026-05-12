from fastapi.testclient import TestClient

from ai_worker.interfaces.api.main import create_app


def test_rerank_endpoint_returns_stable_rank() -> None:
    client = TestClient(create_app())

    response = client.post("/internal/rerank", json={
        "tenantId": "tenant_01",
        "query": "什么是一期范围？",
        "candidates": [
            {"chunkId": "chunk_01", "sourceType": "DOCUMENT", "text": "一期范围包括...", "rrfScore": 0.9},
            {"chunkId": "chunk_02", "sourceType": "PRIMARY_TRANSCRIPT", "text": "会议纪要...", "rrfScore": 0.7},
            {"chunkId": "chunk_03", "sourceType": "AI_SUMMARY", "text": "总结...", "rrfScore": 0.5},
        ],
        "topN": 2,
        "modelVersion": "test-v0",
    })

    assert response.status_code == 200
    data = response.json()
    assert data["modelVersion"] == "test-v0"
    assert len(data["items"]) == 2
    assert data["items"][0]["rank"] == 1
    assert data["items"][0]["rerankScore"] >= data["items"][1]["rerankScore"]


def test_rerank_endpoint_empty_candidates() -> None:
    client = TestClient(create_app())

    response = client.post("/internal/rerank", json={
        "tenantId": "tenant_01",
        "query": "",
        "candidates": [],
        "topN": 8,
        "modelVersion": "test-v0",
    })

    assert response.status_code == 200
    data = response.json()
    assert data["items"] == []


def test_rerank_endpoint_truncates_to_top_n() -> None:
    client = TestClient(create_app())

    candidates = [
        {"chunkId": f"chunk_{i:02d}", "sourceType": "DOCUMENT", "text": f"text_{i}", "rrfScore": 1.0 - i * 0.1}
        for i in range(10)
    ]

    response = client.post("/internal/rerank", json={
        "tenantId": "tenant_01",
        "query": "test query",
        "candidates": candidates,
        "topN": 3,
        "modelVersion": "test-v0",
    })

    assert response.status_code == 200
    data = response.json()
    assert len(data["items"]) == 3
