"""Phase J — workstation SPA mount + fallback tests.

The workstation SPA lives at ``/workstation/`` and uses ``BrowserRouter``,
so a hard reload on a deep route (``/workstation/meetings/new``) must
also return the SPA shell. The ``SpaStaticFiles`` subclass in
``main.py`` handles this; this module pins that behaviour.
"""

from __future__ import annotations

from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from ai_worker.common.config import settings


def _build_spa_dist(tmp_path: Path) -> Path:
    """Minimal mock of a Vite build artefact under ``/workstation/``."""
    dist = tmp_path / "workstation-dist"
    (dist / "assets").mkdir(parents=True)
    (dist / "index.html").write_text(
        "<!doctype html><html><body><div id=root></div>"
        '<script type=module src="/workstation/assets/index.js"></script>'
        "</body></html>",
        encoding="utf-8",
    )
    (dist / "assets" / "index.js").write_text(
        "console.log('workstation bundle');", encoding="utf-8"
    )
    return dist


def _fresh_app() -> TestClient:
    """Force a re-import of main so the StaticFiles mount picks up the
    monkey-patched settings — the mount is wired at create_app() time and
    captures ``settings.admin_ui_dist_path`` once."""
    from ai_worker.interfaces.api import main as main_module

    return TestClient(main_module.create_app())


def test_workstation_serves_index_at_root(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    dist = _build_spa_dist(tmp_path)
    monkeypatch.setattr(settings, "admin_ui_dist_path", str(dist))

    client = _fresh_app()
    response = client.get("/workstation/")

    assert response.status_code == 200
    body = response.text
    assert "<div id=root>" in body
    # base="/workstation/" must have rewritten the script src in the build.
    assert "/workstation/assets/index.js" in body


def test_app_root_redirects_to_workstation_when_ui_is_mounted(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    dist = _build_spa_dist(tmp_path)
    monkeypatch.setattr(settings, "admin_ui_dist_path", str(dist))

    client = _fresh_app()
    response = client.get("/", follow_redirects=False)

    assert response.status_code == 307
    assert response.headers["location"] == "/workstation/"


def test_app_root_returns_entrypoint_hint_when_ui_is_not_mounted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "admin_ui_dist_path", None)

    client = _fresh_app()
    response = client.get("/")

    assert response.status_code == 200
    assert response.json()["workstationUrl"] == "/workstation/"
    assert response.json()["workstationMounted"] is False


def test_workstation_serves_real_asset_under_subpath(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    dist = _build_spa_dist(tmp_path)
    monkeypatch.setattr(settings, "admin_ui_dist_path", str(dist))

    client = _fresh_app()
    response = client.get("/workstation/assets/index.js")

    assert response.status_code == 200
    assert "workstation bundle" in response.text


def test_workstation_falls_back_to_index_for_spa_route(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Deep BrowserRouter routes hit when a user hard-reloads or shares a link."""
    dist = _build_spa_dist(tmp_path)
    monkeypatch.setattr(settings, "admin_ui_dist_path", str(dist))

    client = _fresh_app()
    response = client.get("/workstation/meetings/new")

    assert response.status_code == 200
    # Must be the SPA shell, not a 404 page.
    assert "<div id=root>" in response.text


def test_root_assets_still_404_when_workstation_mounted(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Phase J regression — assets must live under /workstation/, not root.

    A previous bug shipped the build with ``base: "/"``, so the index.html
    referenced ``/assets/...`` and 404'd. This test pins that ``/assets/...``
    has NO route at the FastAPI app root — assets are only reachable via
    the ``/workstation/`` mount.
    """
    dist = _build_spa_dist(tmp_path)
    monkeypatch.setattr(settings, "admin_ui_dist_path", str(dist))

    client = _fresh_app()
    response = client.get("/assets/index.js")

    assert response.status_code == 404


def test_workstation_missing_asset_returns_404_not_html(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """A 404 on a missing JS / CSS must NOT degrade into index.html.

    Earlier the SpaStaticFiles fallback was too aggressive and returned
    index.html for every 404 under /workstation/*. The browser would then
    try to execute the HTML as a JS module and fail with an obscure parse
    error. Phase J tightens the rule so anything under ``assets/`` or with
    a known asset extension keeps its real 404.
    """
    dist = _build_spa_dist(tmp_path)
    monkeypatch.setattr(settings, "admin_ui_dist_path", str(dist))

    client = _fresh_app()

    response = client.get("/workstation/assets/missing-bundle.js")
    assert response.status_code == 404

    # Asset extension outside /assets/ (e.g., favicon, source map) — same rule.
    fav = client.get("/workstation/favicon.ico")
    assert fav.status_code == 404


def test_workstation_runtime_config_serves_json(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """``/workstation/runtime-config.json`` must serve a JSON body that the
    SPA bootstrap can parse — and the explicit route must shadow the
    StaticFiles mount even if a ``runtime-config.json`` happens to be
    present in the dist dir (as is the case in dev where the file is
    shipped from ``public/``)."""
    dist = _build_spa_dist(tmp_path)
    (dist / "runtime-config.json").write_text(
        '{"stale": true}\n', encoding="utf-8"
    )
    monkeypatch.setattr(settings, "admin_ui_dist_path", str(dist))
    monkeypatch.setattr(settings, "auth_login_url", None)

    client = _fresh_app()
    response = client.get("/workstation/runtime-config.json")

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/json")
    body = response.json()
    # FastAPI route wins over the dist/ copy — the stale flag from the
    # static fixture must NOT leak through.
    assert "stale" not in body


def test_workstation_runtime_config_reflects_auth_login_url(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    dist = _build_spa_dist(tmp_path)
    monkeypatch.setattr(settings, "admin_ui_dist_path", str(dist))
    monkeypatch.setattr(
        settings, "auth_login_url", "https://meeting-api.internal/auth/login"
    )

    client = _fresh_app()
    body = client.get("/workstation/runtime-config.json").json()

    assert body["authLoginUrl"] == "https://meeting-api.internal/auth/login"


def test_python_hosted_workstation_proxies_login_to_java(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from ai_worker.interfaces.api import main as main_module

    seen: dict[str, object] = {}

    class FakeAsyncClient:
        def __init__(self, *, base_url: str, timeout: float) -> None:
            seen["base_url"] = base_url
            seen["timeout"] = timeout

        async def __aenter__(self) -> "FakeAsyncClient":
            return self

        async def __aexit__(self, *_args: object) -> None:
            return None

        async def post(
            self,
            path: str,
            *,
            json: object,
            headers: dict[str, str],
        ) -> httpx.Response:
            seen["path"] = path
            seen["json"] = json
            seen["headers"] = headers
            return httpx.Response(
                200,
                json={
                    "success": True,
                    "data": {"accessToken": "jwt.header.signature"},
                    "error": None,
                    "requestId": "r",
                    "traceId": "t",
                },
                headers={
                    "Content-Type": "application/json",
                    "Set-Cookie": "refresh=abc; HttpOnly; Path=/",
                },
            )

    monkeypatch.setattr(settings, "java_api_base_url", "http://10.9.50.179:8080")
    monkeypatch.setattr(main_module.httpx, "AsyncClient", FakeAsyncClient)

    client = TestClient(main_module.create_app())
    response = client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "admin123"},
        headers={"X-Request-Id": "r", "X-Trace-Id": "t"},
    )

    assert response.status_code == 200
    assert response.json()["data"]["accessToken"] == "jwt.header.signature"
    assert response.headers["set-cookie"] == "refresh=abc; HttpOnly; Path=/"
    assert seen["base_url"] == "http://10.9.50.179:8080"
    assert seen["path"] == "/api/auth/login"
    assert seen["json"] == {"username": "admin", "password": "admin123"}
    assert seen["headers"] == {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "X-Request-Id": "r",
        "X-Trace-Id": "t",
    }


def test_python_hosted_workstation_proxies_processing_task_detail_to_java(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from ai_worker.interfaces.api import main as main_module

    seen: dict[str, object] = {}

    class FakeAsyncClient:
        def __init__(self, *, base_url: str, timeout: float) -> None:
            seen["base_url"] = base_url
            seen["timeout"] = timeout

        async def __aenter__(self) -> "FakeAsyncClient":
            return self

        async def __aexit__(self, *_args: object) -> None:
            return None

        async def get(
            self,
            path: str,
            *,
            headers: dict[str, str],
        ) -> httpx.Response:
            seen["path"] = path
            seen["headers"] = headers
            return httpx.Response(
                200,
                json={
                    "success": True,
                    "data": {"taskId": "task 1", "status": "RUNNING"},
                    "error": None,
                    "requestId": "java-r",
                    "traceId": "java-t",
                },
                headers={"Content-Type": "application/json"},
            )

    monkeypatch.setattr(settings, "java_api_base_url", "http://meeting-api:8080")
    monkeypatch.setattr(main_module.httpx, "AsyncClient", FakeAsyncClient)

    client = TestClient(main_module.create_app())
    response = client.get(
        "/api/processing-tasks/task%201",
        headers={
            "Authorization": "Bearer admin.jwt",
            "X-Request-Id": "r",
            "X-Trace-Id": "t",
        },
    )

    assert response.status_code == 200
    assert response.json()["data"]["taskId"] == "task 1"
    assert seen["base_url"] == "http://meeting-api:8080"
    assert seen["path"] == "/api/processing-tasks/task%201"
    assert seen["headers"] == {
        "Accept": "application/json",
        "Authorization": "Bearer admin.jwt",
        "X-Request-Id": "r",
        "X-Trace-Id": "t",
    }


def test_python_hosted_workstation_streams_processing_task_sse_from_java(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from ai_worker.interfaces.api import main as main_module

    seen: dict[str, object] = {}

    class FakeStreamResponse:
        status_code = 200
        headers = {"content-type": "text/event-stream"}

        async def __aenter__(self) -> "FakeStreamResponse":
            return self

        async def __aexit__(self, *_args: object) -> None:
            return None

        async def aiter_bytes(self):
            yield b'event: TASK_SNAPSHOT\n'
            yield b'data: {"taskId":"task-1"}\n\n'

    class FakeAsyncClient:
        def __init__(self, *, base_url: str, timeout: float) -> None:
            seen["base_url"] = base_url
            seen["timeout"] = timeout

        async def __aenter__(self) -> "FakeAsyncClient":
            return self

        async def __aexit__(self, *_args: object) -> None:
            return None

        async def aclose(self) -> None:
            return None

        def stream(
            self,
            method: str,
            path: str,
            *,
            headers: dict[str, str],
        ) -> FakeStreamResponse:
            seen["method"] = method
            seen["path"] = path
            seen["headers"] = headers
            return FakeStreamResponse()

    monkeypatch.setattr(settings, "java_api_base_url", "http://meeting-api:8080")
    monkeypatch.setattr(main_module.httpx, "AsyncClient", FakeAsyncClient)

    client = TestClient(main_module.create_app())
    with client.stream(
        "GET",
        "/api/processing-tasks/task-1/events",
        headers={
            "Authorization": "Bearer admin.jwt",
            "X-Request-Id": "r",
            "X-Trace-Id": "t",
            "Last-Event-Id": "task-1:1",
        },
    ) as response:
        body = response.read()

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert b'data: {"taskId":"task-1"}' in body
    assert seen["base_url"] == "http://meeting-api:8080"
    assert seen["method"] == "GET"
    assert seen["path"] == "/api/processing-tasks/task-1/events"
    assert seen["headers"] == {
        "Accept": "text/event-stream",
        "Authorization": "Bearer admin.jwt",
        "X-Request-Id": "r",
        "X-Trace-Id": "t",
        "Last-Event-Id": "task-1:1",
    }


def test_python_hosted_workstation_sse_proxy_preserves_upstream_error_status(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from ai_worker.interfaces.api import main as main_module

    class FakeStreamResponse:
        status_code = 401
        headers = {
            "content-type": "application/json",
            "cache-control": "no-store",
        }

        async def __aenter__(self) -> "FakeStreamResponse":
            return self

        async def __aexit__(self, *_args: object) -> None:
            return None

        async def aiter_bytes(self):
            yield b'{"success":false,"error":{"code":"AUTH_REQUIRED"}}'

    class FakeAsyncClient:
        def __init__(self, *, base_url: str, timeout: float) -> None:
            pass

        async def __aenter__(self) -> "FakeAsyncClient":
            return self

        async def __aexit__(self, *_args: object) -> None:
            return None

        async def aclose(self) -> None:
            return None

        def stream(self, *_args: object, **_kwargs: object) -> FakeStreamResponse:
            return FakeStreamResponse()

    monkeypatch.setattr(settings, "java_api_base_url", "http://meeting-api:8080")
    monkeypatch.setattr(main_module.httpx, "AsyncClient", FakeAsyncClient)

    client = TestClient(main_module.create_app())
    with client.stream("GET", "/api/processing-tasks/task-1/events") as response:
        body = response.read()

    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/json")
    assert body == b'{"success":false,"error":{"code":"AUTH_REQUIRED"}}'
