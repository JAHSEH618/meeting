from __future__ import annotations

import hashlib
import hmac
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


def test_hardware_endpoint_reports_device_resolution() -> None:
    """Phase J ML hardening — operators need a quick way to see whether
    torch, MPS, and FunASR/pyannote installed correctly. The endpoint must
    work in fake mode without importing torch unsuccessfully (i.e. should
    not 500 when torch is absent)."""
    client = TestClient(create_app())
    response = client.get("/internal/hardware")

    assert response.status_code == 200
    body = response.json()
    assert "torch" in body
    assert "packages" in body
    # Per-model device resolution must be present; values vary by host
    # (auto → cpu in fake mode) so we only assert the keys exist.
    assert set(body["resolvedDevices"]) == {"bgeM3", "bgeReranker", "asr", "diarization"}


def test_warmup_capability_filter_selects_asr_only(monkeypatch: pytest.MonkeyPatch) -> None:
    """``?capabilities=asr`` must not touch the embedding / rerank runtimes.

    Pinned because the default warmup intentionally stays on embedding+rerank
    for back-compat; a regression here would either silently warm everything
    (and crash CPU-only dev boxes) or silently warm nothing (and leave the
    ASR cold start in the user-visible request path).

    We monkeypatch each registry getter in the main module (where it's
    bound) and assert the asr getter was invoked while embedding/rerank
    were not — the response body alone can't prove negative behaviour
    because ``_all_model_infos`` still calls every getter to populate the
    summary.
    """
    from ai_worker.interfaces.api import main as main_module

    calls: dict[str, int] = {"asr": 0, "bge_m3": 0, "bge_reranker": 0, "diar": 0}

    def _patch(name: str, real):
        def _wrapped():
            calls[name] += 1
            return real()
        return _wrapped

    # Snapshot the originals before patching so the wrapper still resolves
    # the real singleton (we only want to count, not break the response).
    orig_asr = main_module.get_asr_runtime
    orig_m3 = main_module.get_bge_m3
    orig_rr = main_module.get_bge_reranker
    orig_d = main_module.get_diarization_runtime
    monkeypatch.setattr(main_module, "get_asr_runtime", _patch("asr", orig_asr))
    monkeypatch.setattr(main_module, "get_bge_m3", _patch("bge_m3", orig_m3))
    monkeypatch.setattr(main_module, "get_bge_reranker", _patch("bge_reranker", orig_rr))
    monkeypatch.setattr(main_module, "get_diarization_runtime", _patch("diar", orig_d))
    # Re-bind the capability table to the patched getters; the module-level
    # dict captured the originals at import time.
    monkeypatch.setattr(
        main_module,
        "_CAPABILITY_TO_RUNTIMES",
        {
            "embedding": ("bge-m3", lambda: main_module.get_bge_m3()),
            "rerank": ("bge-reranker-v2-m3", lambda: main_module.get_bge_reranker()),
            "asr": ("qwen3-asr", lambda: main_module.get_asr_runtime()),
            "diarization": ("pyannote-diarization", lambda: main_module.get_diarization_runtime()),
        },
    )

    client = TestClient(main_module.create_app())
    headers = _auth_headers("POST", "/internal/models/warmup", b"")
    response = client.post(
        "/internal/models/warmup?capabilities=asr", headers=headers
    )

    assert response.status_code == 200
    # _all_model_infos still touches every getter once for the response
    # summary, so we assert ASR was called *more* than embedding/rerank
    # (i.e. once for the warmup selection AND once for the summary).
    assert calls["asr"] >= 2
    assert calls["bge_m3"] <= 1
    assert calls["bge_reranker"] <= 1


def test_warmup_rejects_unknown_capability() -> None:
    client = TestClient(create_app())
    headers = _auth_headers("POST", "/internal/models/warmup", b"")
    response = client.post(
        "/internal/models/warmup?capabilities=quantum", headers=headers
    )

    assert response.status_code == 400
    body = response.json()
    assert body["error"]["code"] == "WARMUP_UNKNOWN_CAPABILITY"


def test_ready_endpoint_fails_when_real_mode_dep_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Phase J ML hardening — fake=false image without FlagEmbedding must
    fail readiness *before* the first task crashes.

    Without this guard, a misconfigured CUDA build (no ``UV_EXTRAS=real-models``)
    Pod would Ready, accept a real ASR/embed/rerank task, and only then crash
    inside ``_load_model_blocking`` — the user-visible symptom is a 503 mid-
    task instead of a kubelet-level NotReady. The fix surfaces the dep
    problem at probe time so a Deployment rollout halts cleanly."""
    monkeypatch.setattr(settings, "use_fake_runtime", False)
    registry.reset_for_tests()

    client = TestClient(create_app())
    # FlagEmbedding is not installed in the dev/test env, so the embedding
    # and rerank runtimes must show ERROR + a recognisable lastError.
    response = client.get("/internal/ready")
    body = response.json()

    failed_names = {m["name"] for m in body["models"] if m["status"] == "ERROR"}
    assert {"bge-m3", "bge-reranker-v2-m3"}.issubset(failed_names)
    assert response.status_code == 503
    sample = next(m for m in body["models"] if m["name"] == "bge-m3")
    assert sample["lastError"] is not None
    assert "FlagEmbedding" in sample["lastError"]
