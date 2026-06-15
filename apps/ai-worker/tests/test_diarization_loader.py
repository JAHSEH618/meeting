"""Tests for ai_worker.model_runtime.diarization_loader.DiarizationModelLoader.

Verifies the real→fake fallback logic, checksum validation, and idempotent
load behavior without requiring actual pyannote-audio model weights.
"""

from __future__ import annotations

import pytest

from ai_worker.model_runtime.diarization_loader import DiarizationModelLoader
from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntime
from ai_worker.pipeline.audio.preprocess import AudioMetadata


def _meta(duration_ms: int = 5_000) -> AudioMetadata:
    """Create minimal AudioMetadata for diarize calls."""
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
    loader = DiarizationModelLoader(model_path=tmp_path / "nonexistent")
    assert not loader.is_real_available()

    runtime = await loader.load()
    assert runtime is not None
    assert loader.runtime_type == "fake"
    assert runtime.use_fake is True
    assert runtime.status == "READY"


@pytest.mark.asyncio
async def test_loader_uses_real_when_weights_exist(tmp_path):
    """Loader should attempt real runtime when model directory exists."""
    weights_dir = tmp_path / "pyannote"
    weights_dir.mkdir()
    # Create a dummy config.yaml to make it look like a model directory
    (weights_dir / "config.yaml").write_text("pipeline:\n  name: speaker-diarization\n")

    loader = DiarizationModelLoader(model_path=weights_dir, device="cpu")
    assert loader.is_real_available()

    # Note: This will fail during ensure_loaded() because pyannote.audio isn't
    # installed in test environment (requires --extra real-diarization), but
    # that's expected — the loader correctly identified weights as "available"
    # and attempted to load.
    from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntimeError
    with pytest.raises(PyannoteDiarizationRuntimeError):
        await loader.load()

    assert loader.runtime_type == "real"
    assert loader.runtime.use_fake is False
    # Status will be ERROR because pyannote.audio isn't available
    assert loader.runtime.status == "ERROR"


@pytest.mark.asyncio
async def test_loader_rejects_checksum_mismatch_and_falls_back(tmp_path):
    """Loader should fall back to fake when checksum doesn't match."""
    weights_dir = tmp_path / "pyannote"
    weights_dir.mkdir()
    (weights_dir / "config.yaml").write_bytes(b"fake config")

    from ai_worker.observability.model_checksum import compute_checksum
    actual_checksum = compute_checksum(str(weights_dir))

    # Use a different checksum than what actually exists
    wrong_checksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

    loader = DiarizationModelLoader(
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
    weights_dir = tmp_path / "pyannote"
    weights_dir.mkdir()
    (weights_dir / "config.yaml").write_bytes(b"deterministic fake config")

    from ai_worker.observability.model_checksum import compute_checksum
    correct_checksum = compute_checksum(str(weights_dir))

    loader = DiarizationModelLoader(
        model_path=weights_dir,
        expected_checksum=correct_checksum,
        device="cpu",
    )
    # Checksum matched, so loader will try to use real mode
    from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntimeError
    with pytest.raises(PyannoteDiarizationRuntimeError):
        await loader.load()

    assert loader.runtime_type == "real"
    # But actual loading will fail because pyannote.audio isn't installed
    assert loader.runtime.status == "ERROR"


@pytest.mark.asyncio
async def test_loader_is_idempotent(tmp_path):
    """Calling load() multiple times should return the same instance."""
    loader = DiarizationModelLoader(model_path=tmp_path / "missing")
    runtime1 = await loader.load()
    runtime2 = await loader.load()
    assert runtime1 is runtime2
    assert loader.runtime_type == "fake"


@pytest.mark.asyncio
async def test_fake_runtime_can_diarize(tmp_path):
    """Fake runtime from loader should work for diarization."""
    loader = DiarizationModelLoader(model_path=tmp_path / "missing")
    runtime = await loader.load()

    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"")
    turns = await runtime.diarize(audio, _meta())

    assert len(turns) >= 1
    assert turns[0].speaker_label
    assert turns[0].start_ms >= 0
    assert turns[0].end_ms > turns[0].start_ms


def test_runtime_type_is_none_before_load(tmp_path):
    """runtime_type should be None until load() is called."""
    loader = DiarizationModelLoader(model_path=tmp_path / "missing")
    assert loader.runtime_type is None
    assert loader.runtime is None


@pytest.mark.asyncio
async def test_loader_respects_device_parameter(tmp_path):
    """Device parameter should be passed through to runtime."""
    weights_dir = tmp_path / "pyannote"
    weights_dir.mkdir()
    (weights_dir / "config.yaml").write_text("pipeline:\n  name: speaker-diarization\n")

    loader = DiarizationModelLoader(model_path=weights_dir, device="cuda")

    # Will fail during load because pyannote.audio isn't installed
    from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntimeError
    with pytest.raises(PyannoteDiarizationRuntimeError):
        await loader.load()

    # Runtime should have received the cuda device even though load failed
    assert loader.runtime.device == "cuda"


@pytest.mark.asyncio
async def test_loader_respects_min_max_speakers(tmp_path):
    """min_speakers and max_speakers should be passed through to runtime."""
    weights_dir = tmp_path / "pyannote"
    weights_dir.mkdir()
    (weights_dir / "config.yaml").write_text("pipeline:\n  name: speaker-diarization\n")

    loader = DiarizationModelLoader(
        model_path=weights_dir,
        device="cpu",
        min_speakers=2,
        max_speakers=5,
    )

    # Will fail during load because pyannote.audio isn't installed
    from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntimeError
    with pytest.raises(PyannoteDiarizationRuntimeError):
        await loader.load()

    # Runtime should have received the speaker constraints
    assert loader.runtime._min_speakers == 2
    assert loader.runtime._max_speakers == 5
