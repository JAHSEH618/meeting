"""Phase J — workstation SPA mount + fallback tests.

The workstation SPA lives at ``/workstation/`` and uses ``BrowserRouter``,
so a hard reload on a deep route (``/workstation/meetings/new``) must
also return the SPA shell. The ``SpaStaticFiles`` subclass in
``main.py`` handles this; this module pins that behaviour.
"""

from __future__ import annotations

from pathlib import Path

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
