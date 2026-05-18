"""Tests for ai_worker.model_runtime.diarization.PyannoteDiarizationRuntime
(final-check.md A1.7)."""

from __future__ import annotations

import pytest

from ai_worker.model_runtime.diarization import (
    PyannoteDiarizationRuntime,
    PyannoteDiarizationRuntimeError,
)
from ai_worker.pipeline.audio.preprocess import AudioMetadata


def _meta(duration_ms: int = 5_000) -> AudioMetadata:
    return AudioMetadata(
        duration_ms=duration_ms,
        sample_rate_hz=16_000,
        channels=1,
        codec="pcm_s16le",
        bitrate=256_000,
        format_name="wav",
    )


@pytest.mark.asyncio
async def test_fake_mode_starts_ready_and_returns_single_turn(tmp_path):
    runtime = PyannoteDiarizationRuntime(use_fake=True)
    assert runtime.status == "READY"
    assert runtime.use_fake is True
    assert runtime.device == "fake"
    assert runtime.model_version == PyannoteDiarizationRuntime.FAKE_MODEL_VERSION

    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"")
    turns = await runtime.diarize(audio, _meta())
    assert len(turns) == 1
    assert turns[0].speaker_label == "SPEAKER_00"


@pytest.mark.asyncio
async def test_real_mode_raises_when_weights_missing(tmp_path):
    runtime = PyannoteDiarizationRuntime(
        use_fake=False,
        models_dir=tmp_path / "missing",
        device="cpu",
    )
    assert runtime.status == "NOT_LOADED"
    with pytest.raises(PyannoteDiarizationRuntimeError) as ex:
        await runtime.ensure_loaded()
    assert ex.value.error_code == "DIARIZATION_FAILED"
    assert runtime.status == "ERROR"


@pytest.mark.asyncio
async def test_real_mode_diarize_before_load_raises(tmp_path):
    runtime = PyannoteDiarizationRuntime(
        use_fake=False,
        models_dir=tmp_path / "missing",
        device="cpu",
    )
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"")
    with pytest.raises(PyannoteDiarizationRuntimeError) as ex:
        await runtime.diarize(audio, _meta())
    assert ex.value.error_code == "DIARIZATION_FAILED"


@pytest.mark.asyncio
async def test_empty_duration_raises_immediately(tmp_path):
    runtime = PyannoteDiarizationRuntime(use_fake=True)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"")
    with pytest.raises(PyannoteDiarizationRuntimeError) as ex:
        await runtime.diarize(audio, _meta(duration_ms=0))
    assert ex.value.error_code == "DIARIZATION_EMPTY_TURNS"


def test_model_version_flips_with_use_fake():
    fake = PyannoteDiarizationRuntime(use_fake=True)
    real = PyannoteDiarizationRuntime(use_fake=False)
    assert fake.model_version == PyannoteDiarizationRuntime.FAKE_MODEL_VERSION
    assert real.model_version == PyannoteDiarizationRuntime.REAL_MODEL_VERSION
    assert real.status == "NOT_LOADED"
