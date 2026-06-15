"""Tests for ai_worker.model_runtime.asr_loader.ASRModelLoader.

Verifies the real→fake fallback logic, checksum validation, and idempotent
load behavior without requiring actual Qwen3-ASR model weights.
"""

from __future__ import annotations

import pytest

from ai_worker.model_runtime.asr_loader import ASRModelLoader
from ai_worker.model_runtime.asr import Qwen3AsrRuntime
from ai_worker.pipeline.audio.preprocess import AudioMetadata


def _meta(duration_ms: int = 5_000) -> AudioMetadata:
    """Create minimal AudioMetadata for transcribe calls."""
    return AudioMetadata(
        duration_ms=duration_ms,
        sample_rate_hz=16_000,
        channels=1,
        codec="pcm_s16le",
        bitrate=256_000,
        format_name="wav",
    )


@pytest.mark.asyncio
async def test_loader_falls_back_to_fake_when_weights_missing(tmp_path):
    """Loader should use fake runtime when model path doesn't exist."""
    loader = ASRModelLoader(model_path=tmp_path / "nonexistent")
    assert not loader.is_real_available()

    runtime = await loader.load()
    assert runtime is not None
    assert loader.runtime_type == "fake"
    assert runtime.use_fake is True
    assert runtime.status == "READY"


@pytest.mark.asyncio
async def test_loader_uses_real_when_weights_exist(tmp_path):
    """Loader should attempt real runtime when model directory exists."""
    weights_dir = tmp_path / "qwen3-asr"
    weights_dir.mkdir()
    # Create a dummy file to make it look like a model directory
    (weights_dir / "config.json").write_text("{}")

    loader = ASRModelLoader(model_path=weights_dir, device="cpu")
    assert loader.is_real_available()

    # Note: This will fail during ensure_loaded() because funasr isn't installed
    # in test environment (requires --extra real-models), but that's expected —
    # the loader correctly identified weights as "available" and attempted to load.
    from ai_worker.model_runtime.asr import Qwen3AsrRuntimeError
    with pytest.raises(Qwen3AsrRuntimeError):
        await loader.load()

    assert loader.runtime_type == "real"
    assert loader.runtime.use_fake is False
    # Status will be ERROR because funasr isn't available
    assert loader.runtime.status == "ERROR"


@pytest.mark.asyncio
async def test_loader_rejects_checksum_mismatch_and_falls_back(tmp_path):
    """Loader should fall back to fake when checksum doesn't match."""
    weights_dir = tmp_path / "qwen3-asr"
    weights_dir.mkdir()
    (weights_dir / "model.bin").write_bytes(b"fake weights")

    from ai_worker.observability.model_checksum import compute_checksum
    actual_checksum = compute_checksum(str(weights_dir))

    # Use a different checksum than what actually exists
    wrong_checksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

    loader = ASRModelLoader(
        model_path=weights_dir,
        expected_checksum=wrong_checksum,
    )
    assert loader.is_real_available()

    runtime = await loader.load()
    # Should fall back to fake due to checksum mismatch
    assert loader.runtime_type == "fake"
    assert runtime.use_fake is True


@pytest.mark.asyncio
async def test_loader_accepts_correct_checksum(tmp_path):
    """Loader should use real runtime when checksum matches."""
    weights_dir = tmp_path / "qwen3-asr"
    weights_dir.mkdir()
    (weights_dir / "model.bin").write_bytes(b"deterministic fake weights")

    from ai_worker.observability.model_checksum import compute_checksum
    correct_checksum = compute_checksum(str(weights_dir))

    loader = ASRModelLoader(
        model_path=weights_dir,
        expected_checksum=correct_checksum,
        device="cpu",
    )
    # Checksum matched, so loader will try to use real mode
    from ai_worker.model_runtime.asr import Qwen3AsrRuntimeError
    with pytest.raises(Qwen3AsrRuntimeError):
        await loader.load()

    assert loader.runtime_type == "real"
    # But actual loading will fail because funasr isn't installed
    assert loader.runtime.status == "ERROR"


@pytest.mark.asyncio
async def test_loader_is_idempotent(tmp_path):
    """Calling load() multiple times should return the same instance."""
    loader = ASRModelLoader(model_path=tmp_path / "missing")
    runtime1 = await loader.load()
    runtime2 = await loader.load()
    assert runtime1 is runtime2
    assert loader.runtime_type == "fake"


@pytest.mark.asyncio
async def test_fake_runtime_can_transcribe(tmp_path):
    """Fake runtime from loader should work for transcription."""
    loader = ASRModelLoader(model_path=tmp_path / "missing")
    runtime = await loader.load()

    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"")
    segments = await runtime.transcribe(audio, _meta(), language="zh")

    assert len(segments) >= 1
    assert segments[0].text
    assert segments[0].start_ms >= 0


def test_runtime_type_is_none_before_load(tmp_path):
    """runtime_type should be None until load() is called."""
    loader = ASRModelLoader(model_path=tmp_path / "missing")
    assert loader.runtime_type is None
    assert loader.runtime is None


@pytest.mark.asyncio
async def test_loader_respects_device_parameter(tmp_path):
    """Device parameter should be passed through to runtime."""
    weights_dir = tmp_path / "qwen3-asr"
    weights_dir.mkdir()
    (weights_dir / "config.json").write_text("{}")

    loader = ASRModelLoader(model_path=weights_dir, device="cuda")

    # Will fail during load because funasr isn't installed
    from ai_worker.model_runtime.asr import Qwen3AsrRuntimeError
    with pytest.raises(Qwen3AsrRuntimeError):
        await loader.load()

    # Runtime should have received the cuda device even though load failed
    assert loader.runtime.device == "cuda"
