from __future__ import annotations

import math
from pathlib import Path

import pytest

from ai_worker.pipeline.audio.preprocess import AudioMetadata
from ai_worker.pipeline.diarization.runtime import SpeakerTurn
from ai_worker.pipeline.speaker.runtime import (
    DeterministicSpeakerEmbeddingRuntime,
    SpeakerEmbeddingRuntimeError,
)


def _metadata() -> AudioMetadata:
    return AudioMetadata(
        duration_ms=2000,
        sample_rate_hz=16000,
        channels=1,
        codec="pcm_s16le",
        bitrate=256000,
        format_name="wav",
    )


def _turn(label: str = "SPEAKER_00") -> SpeakerTurn:
    return SpeakerTurn(speaker_label=label, start_ms=0, end_ms=1500, confidence=0.85)


@pytest.mark.asyncio
async def test_embedding_is_192_dim_and_l2_normalized(tmp_path: Path) -> None:
    audio = tmp_path / "a.wav"
    audio.write_bytes(b"audio")
    runtime = DeterministicSpeakerEmbeddingRuntime()

    embedding = await runtime.embed(audio, _metadata(), _turn())

    assert embedding.dimension == 192
    assert len(embedding.values) == 192
    norm = math.sqrt(sum(v * v for v in embedding.values))
    assert abs(norm - 1.0) < 1e-6
    assert embedding.speaker_label == "SPEAKER_00"
    assert embedding.model_version == "deterministic-speaker-v0"
    assert len(embedding.checksum) == 64
    assert embedding.quality_score == pytest.approx(0.85)


@pytest.mark.asyncio
async def test_same_input_produces_same_embedding(tmp_path: Path) -> None:
    audio = tmp_path / "a.wav"
    audio.write_bytes(b"audio")
    runtime = DeterministicSpeakerEmbeddingRuntime()

    first = await runtime.embed(audio, _metadata(), _turn())
    second = await runtime.embed(audio, _metadata(), _turn())

    assert first.values == second.values
    assert first.checksum == second.checksum


@pytest.mark.asyncio
async def test_different_speakers_produce_different_embeddings(tmp_path: Path) -> None:
    audio = tmp_path / "a.wav"
    audio.write_bytes(b"audio")
    runtime = DeterministicSpeakerEmbeddingRuntime()

    first = await runtime.embed(audio, _metadata(), _turn("SPEAKER_00"))
    second = await runtime.embed(audio, _metadata(), _turn("SPEAKER_01"))

    assert first.values != second.values
    assert first.checksum != second.checksum


@pytest.mark.asyncio
async def test_invalid_turn_boundaries_raise(tmp_path: Path) -> None:
    audio = tmp_path / "a.wav"
    audio.write_bytes(b"audio")
    runtime = DeterministicSpeakerEmbeddingRuntime()
    bad = SpeakerTurn(speaker_label="SPEAKER_00", start_ms=200, end_ms=200, confidence=0.5)

    with pytest.raises(SpeakerEmbeddingRuntimeError):
        await runtime.embed(audio, _metadata(), bad)
