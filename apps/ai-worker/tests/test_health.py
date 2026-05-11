from fastapi.testclient import TestClient

from ai_worker.interfaces.api.main import create_app


def test_health_endpoint() -> None:
    client = TestClient(create_app())

    response = client.get("/internal/health")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"
