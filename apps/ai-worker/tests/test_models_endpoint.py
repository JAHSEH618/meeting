from __future__ import annotations

import hashlib
import hmac
import json
import secrets
from datetime import datetime, timezone
from pathlib import Path

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
    nonce = f"models_test_{secrets.token_hex(4)}"
    signature = _sign(method, path, body, timestamp, nonce)
    return {
        "X-Request-Id": "req_test_models",
        "X-Trace-Id": "trace_test_models",
        "X-Tenant-Id": "tenant_01",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
        "Content-Type": "application/json",
    }


@pytest.fixture(autouse=True)
def _reset_registry() -> None:
    """Each test gets a fresh registry so triggered-state assertions hold."""
    registry.reset_for_tests()
    yield
    registry.reset_for_tests()


def test_get_models_returns_fake_runtimes_ready() -> None:
    client = TestClient(create_app())
    headers = _auth_headers("GET", "/internal/models", b"")

    response = client.get("/internal/models", headers=headers)

    assert response.status_code == 200
    payload = response.json()
    assert payload["success"] is True
    models = payload["data"]["models"]
    assert len(models) == 4

    by_name = {m["name"]: m for m in models}
    assert set(by_name) == {"bge-m3", "bge-reranker-v2-m3", "qwen3-asr", "pyannote-diarization"}

    for info in models:
        assert info["status"] == "READY"
        assert info["device"] == "fake"
        assert info["useFake"] is True
        assert info["checksum"] is None
        assert info["lastError"] is None
        # Each fake runtime uses its own deterministic placeholder version
        # (see *_FAKE_MODEL_VERSION constants); not all of them end in
        # "-fake-v0" — just assert the runtime advertises *some* version.
        assert isinstance(info["version"], str) and info["version"]


def test_get_models_rejects_missing_signature() -> None:
    client = TestClient(create_app())
    response = client.get("/internal/models")
    # Missing X-Signature header → FastAPI returns 422 for the required Header.
    assert response.status_code == 422


def test_get_models_rejects_invalid_signature() -> None:
    client = TestClient(create_app())
    headers = _auth_headers("GET", "/internal/models", b"")
    headers["X-Signature"] = "hmac-sha256=" + "0" * 64

    response = client.get("/internal/models", headers=headers)

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "MODELS_AUTH_FAILED"


def test_post_warmup_in_fake_mode_reports_not_triggered() -> None:
    client = TestClient(create_app())
    headers = _auth_headers("POST", "/internal/models/warmup", b"")

    response = client.post("/internal/models/warmup", headers=headers)

    assert response.status_code == 200
    payload = response.json()
    assert payload["success"] is True
    # Fake mode starts READY → nothing to trigger.
    assert payload["data"]["triggered"] is False
    assert len(payload["data"]["models"]) == 4
    for info in payload["data"]["models"]:
        assert info["status"] == "READY"


def test_post_warmup_rejects_invalid_signature() -> None:
    client = TestClient(create_app())
    headers = _auth_headers("POST", "/internal/models/warmup", b"")
    headers["X-Signature"] = "hmac-sha256=" + "1" * 64

    response = client.post("/internal/models/warmup", headers=headers)

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "MODELS_AUTH_FAILED"


def test_post_warmup_in_real_mode_triggers_loading(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Force real-mode runtimes for this test by flipping the toggle and
    # patching the heavy load so we don't actually hit FlagEmbedding.
    monkeypatch.setattr(settings, "use_fake_runtime", False)
    registry.reset_for_tests()

    bge_m3 = registry.get_bge_m3()
    bge_reranker = registry.get_bge_reranker()
    monkeypatch.setattr(bge_m3, "_load_model_blocking", lambda: setattr(bge_m3, "_model", object()))
    monkeypatch.setattr(
        bge_reranker,
        "_load_model_blocking",
        lambda: setattr(bge_reranker, "_model", object()),
    )

    client = TestClient(create_app())
    headers = _auth_headers("POST", "/internal/models/warmup", b"")

    response = client.post("/internal/models/warmup", headers=headers)

    assert response.status_code == 200
    payload = response.json()
    # Snapshot was taken before ensure_loaded ran; both started NOT_LOADED.
    assert payload["data"]["triggered"] is True
    # Background tasks executed before TestClient returns the response, so
    # by now both runtimes are READY (we patched the load).
    assert bge_m3.status == "READY"
    assert bge_reranker.status == "READY"


def test_get_models_reports_models_dir_when_configured(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "bge_m3_models_dir", "/data/models/bge-m3/v1")
    monkeypatch.setattr(
        settings, "bge_reranker_models_dir", "/data/models/bge-reranker/v1"
    )
    registry.reset_for_tests()

    client = TestClient(create_app())
    headers = _auth_headers("GET", "/internal/models", b"")

    response = client.get("/internal/models", headers=headers)

    assert response.status_code == 200
    by_name = {m["name"]: m for m in response.json()["data"]["models"]}
    assert by_name["bge-m3"]["modelsDir"] == "/data/models/bge-m3/v1"
    assert by_name["bge-reranker-v2-m3"]["modelsDir"] == "/data/models/bge-reranker/v1"


def _stage_weight(tmp_path: Path, sub: str) -> Path:
    target = tmp_path / sub
    target.mkdir(parents=True, exist_ok=True)
    (target / "model.safetensors").write_bytes(b"phase-j-test-bytes")
    return target


def test_get_models_flags_checksum_mismatch(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Phase J — expected checksum != actual should surface as ERROR.

    The runtime is fake-mode (READY), so the only way the model can flip
    to ERROR is the checksum guard. We point ``bge_m3_models_dir`` at a
    real directory with a known hash and pin an obviously-wrong expected
    value; the response must still echo the actual hash so operators can
    diff the two by eye.
    """
    weight_dir = _stage_weight(tmp_path, "bge-m3/v1")
    monkeypatch.setattr(settings, "bge_m3_models_dir", str(weight_dir))
    monkeypatch.setattr(
        settings,
        "bge_m3_expected_checksum",
        "sha256:" + "0" * 64,
    )
    registry.reset_for_tests()

    client = TestClient(create_app())
    headers = _auth_headers("GET", "/internal/models", b"")

    response = client.get("/internal/models", headers=headers)
    assert response.status_code == 200

    by_name = {m["name"]: m for m in response.json()["data"]["models"]}
    bge = by_name["bge-m3"]
    assert bge["status"] == "ERROR"
    assert bge["lastError"] is not None
    assert "checksum mismatch" in bge["lastError"]
    # Other guards are not configured, so the rest stay READY.
    assert by_name["bge-reranker-v2-m3"]["status"] == "READY"


def test_get_models_passes_when_checksum_matches(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    weight_dir = _stage_weight(tmp_path, "bge-m3/v1")
    monkeypatch.setattr(settings, "bge_m3_models_dir", str(weight_dir))
    registry.reset_for_tests()

    # Resolve the expected hash via the same helper the runtime uses, then
    # pin it back as the expected env var — the guard should accept it.
    from ai_worker.observability.model_checksum import compute_checksum

    actual = compute_checksum(str(weight_dir))
    assert actual is not None
    monkeypatch.setattr(settings, "bge_m3_expected_checksum", actual)

    client = TestClient(create_app())
    headers = _auth_headers("GET", "/internal/models", b"")
    response = client.get("/internal/models", headers=headers)
    assert response.status_code == 200

    by_name = {m["name"]: m for m in response.json()["data"]["models"]}
    bge = by_name["bge-m3"]
    assert bge["status"] == "READY"
    assert bge["checksum"] == actual
    assert bge["lastError"] is None


def test_get_models_flags_mismatch_when_weights_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Expected set + models_dir absent → still mismatch, with a clear hint."""
    monkeypatch.setattr(
        settings, "bge_m3_models_dir", "/tmp/definitely-does-not-exist"
    )
    monkeypatch.setattr(
        settings, "bge_m3_expected_checksum", "sha256:" + "f" * 64
    )
    registry.reset_for_tests()

    client = TestClient(create_app())
    headers = _auth_headers("GET", "/internal/models", b"")
    response = client.get("/internal/models", headers=headers)
    assert response.status_code == 200

    by_name = {m["name"]: m for m in response.json()["data"]["models"]}
    bge = by_name["bge-m3"]
    assert bge["status"] == "ERROR"
    assert bge["lastError"] is not None
    assert "<no weights found>" in bge["lastError"]


def test_ready_endpoint_returns_200_in_fake_mode() -> None:
    """No expected checksums configured → ready regardless of fake/real."""
    client = TestClient(create_app())
    response = client.get("/internal/ready")
    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is True
    assert {m["name"] for m in body["models"]} == {
        "bge-m3",
        "bge-reranker-v2-m3",
        "qwen3-asr",
        "pyannote-diarization",
    }


def test_ready_endpoint_returns_503_on_checksum_mismatch(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    weight_dir = _stage_weight(tmp_path, "pyannote/v3.1")
    monkeypatch.setattr(settings, "pyannote_models_dir", str(weight_dir))
    monkeypatch.setattr(
        settings, "pyannote_expected_checksum", "sha256:" + "0" * 64
    )
    registry.reset_for_tests()

    client = TestClient(create_app())
    response = client.get("/internal/ready")

    # 503 so kubelet stops routing traffic; livenessProbe is unaffected.
    assert response.status_code == 503
    body = response.json()
    assert body["ready"] is False
    failed = [m for m in body["models"] if m["status"] == "ERROR"]
    assert any(m["name"] == "pyannote-diarization" for m in failed)
    assert any(
        m["lastError"] and "checksum mismatch" in m["lastError"] for m in failed
    )


def test_ready_endpoint_does_not_require_hmac() -> None:
    """Kubelet probe path — must not carry HMAC headers."""
    client = TestClient(create_app())
    response = client.get("/internal/ready")
    # Either 200 or 503 is fine here; what we are asserting is the absence
    # of a 401/422 (auth/header validation) response.
    assert response.status_code in (200, 503)
