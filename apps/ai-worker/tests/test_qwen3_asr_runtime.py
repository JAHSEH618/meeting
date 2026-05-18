"""Tests for ai_worker.model_runtime.asr.Qwen3AsrRuntime (final-check.md A1.7)."""

from __future__ import annotations

from pathlib import Path

import pytest

from ai_worker.model_runtime.asr import Qwen3AsrRuntime, Qwen3AsrRuntimeError
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
async def test_fake_mode_starts_ready_and_uses_deterministic_runtime(tmp_path):
    runtime = Qwen3AsrRuntime(use_fake=True)
    assert runtime.status == "READY"
    assert runtime.use_fake is True
    assert runtime.device == "fake"
    assert runtime.model_version == Qwen3AsrRuntime.FAKE_MODEL_VERSION

    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"")
    segments = await runtime.transcribe(audio, _meta(), language="zh")
    assert len(segments) == 1
    assert segments[0].start_ms == 0
    assert segments[0].text


@pytest.mark.asyncio
async def test_real_mode_raises_when_weights_missing(tmp_path):
    runtime = Qwen3AsrRuntime(
        use_fake=False,
        models_dir=tmp_path / "does-not-exist",
        device="cpu",
    )
    assert runtime.status == "NOT_LOADED"
    with pytest.raises(Qwen3AsrRuntimeError) as ex:
        await runtime.ensure_loaded()
    assert ex.value.error_code == "ASR_MODEL_TIMEOUT"
    assert runtime.status == "ERROR"
    assert runtime.last_error is not None
    assert "weights not found" in runtime.last_error or "ASR_MODEL_TIMEOUT" in runtime.last_error or "FileNotFoundError" in runtime.last_error


@pytest.mark.asyncio
async def test_real_mode_transcribe_before_load_raises(tmp_path):
    runtime = Qwen3AsrRuntime(
        use_fake=False,
        models_dir=tmp_path / "missing",
        device="cpu",
    )
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"")
    with pytest.raises(Qwen3AsrRuntimeError) as ex:
        await runtime.transcribe(audio, _meta(), language="zh")
    assert ex.value.error_code == "ASR_RUNTIME_ERROR"


@pytest.mark.asyncio
async def test_empty_duration_raises_immediately(tmp_path):
    runtime = Qwen3AsrRuntime(use_fake=True)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"")
    with pytest.raises(Qwen3AsrRuntimeError) as ex:
        await runtime.transcribe(audio, _meta(duration_ms=0), language="zh")
    assert ex.value.error_code == "ASR_EMPTY_RESULT"


def test_model_version_flips_with_use_fake():
    fake = Qwen3AsrRuntime(use_fake=True)
    real = Qwen3AsrRuntime(use_fake=False)
    assert fake.model_version == Qwen3AsrRuntime.FAKE_MODEL_VERSION
    assert real.model_version == Qwen3AsrRuntime.REAL_MODEL_VERSION
    assert real.status == "NOT_LOADED"
