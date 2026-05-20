"""Phase J — lifespan migration smoke tests.

Verifies the FastAPI lifespan context manager (which replaces the
deprecated ``@app.on_event`` decorators) does the right thing:

  * No ``java_api_base_url`` configured → enrollment session cleanup loop
    is NOT started (we don't want a stray task when the workstation BFF
    is off).
  * ``java_api_base_url`` set → cleanup loop is started on enter,
    cancelled on exit.

We use the async TestClient context (``with TestClient(app) as client:``)
because Starlette only runs the lifespan when the context is entered.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from ai_worker.admin.session_store import enrollment_session_store
from ai_worker.common.config import settings
from ai_worker.interfaces.api.main import create_app


@pytest.fixture(autouse=True)
def _stop_cleanup_between_tests() -> None:
    """Tear down any leftover cleanup task; the store is a process singleton."""
    yield
    task = getattr(enrollment_session_store, "_cleanup_task", None)
    if task is not None and not task.done():
        task.cancel()
        # Drop the reference so the next test starts from a clean slate.
        enrollment_session_store._cleanup_task = None  # type: ignore[attr-defined]


def test_lifespan_skips_cleanup_loop_when_no_java_api(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "java_api_base_url", None)
    # Ensure we start from a known state — no leftover cleanup task.
    enrollment_session_store._cleanup_task = None  # type: ignore[attr-defined]

    with TestClient(create_app()) as client:
        response = client.get("/internal/health")
        assert response.status_code == 200
        # Inside the lifespan window — cleanup task must NOT be started
        # because the workstation BFF is off.
        assert enrollment_session_store._cleanup_task is None  # type: ignore[attr-defined]


def test_lifespan_starts_and_stops_cleanup_loop_when_java_api_set(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "java_api_base_url", "http://meeting-api:8080")
    # Force a short cleanup interval so the task doesn't sit forever on
    # the very first ``await asyncio.sleep`` — the test only inspects
    # task liveness, not the eviction body itself.
    monkeypatch.setattr(settings, "admin_session_cleanup_interval_seconds", 60)
    enrollment_session_store._cleanup_task = None  # type: ignore[attr-defined]

    with TestClient(create_app()) as client:
        response = client.get("/internal/health")
        assert response.status_code == 200
        task = enrollment_session_store._cleanup_task  # type: ignore[attr-defined]
        assert task is not None, "cleanup loop should be running inside lifespan"
        assert not task.done()

    # On lifespan exit the task should be cancelled / cleared.
    assert enrollment_session_store._cleanup_task is None  # type: ignore[attr-defined]
