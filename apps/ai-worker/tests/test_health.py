from fastapi.testclient import TestClient

from ai_worker.application.workflows.state import workflow_state_store
from ai_worker.interfaces.api.main import create_app


def test_health_endpoint() -> None:
    client = TestClient(create_app())

    response = client.get("/internal/health")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_workflow_endpoint_returns_recorded_fake_workflow() -> None:
    workflow_state_store.clear()
    workflow_state_store.start(
        task_id="task_api_01",
        task_type="MEETING_FULL_PIPELINE",
        tenant_id="tenant_01",
        attempt_no=1,
        trace_id="trace_api_01",
        steps=["AUDIO_PREPROCESS"],
    )
    workflow_state_store.update_step("task_api_01", "AUDIO_PREPROCESS", "RUNNING", 50)

    client = TestClient(create_app())
    response = client.get("/internal/workflows/task_api_01")

    assert response.status_code == 200
    data = response.json()
    assert data["taskId"] == "task_api_01"
    assert data["status"] == "RUNNING"
    assert data["steps"][0]["progress"] == 50
    workflow_state_store.clear()
