"""Opt-in real-weights smoke tests (M4).

These exercise the *real* model runtimes end-to-end (load + one minimal
inference) through the production registry wiring, so they catch integration
breakage that the mocked unit tests cannot — e.g. a wrong FlagEmbedding device
kwarg, the CAM++ ModelScope call convention, or whether funasr can actually
load the staged Qwen3-ASR weights.

They are gated behind the ``real_models`` marker AND skip themselves cleanly
unless the runtime is in real mode, the Python dep is importable, and the
weights dir is staged. So a normal CI run (fake mode, no weights) shows them as
skipped rather than failed.

Run on a box with weights staged::

    AI_WORKER_USE_FAKE_RUNTIME=false \
    AI_WORKER_USE_FAKE_ASR_RUNTIME=false \
    AI_WORKER_USE_FAKE_DIARIZATION_RUNTIME=false \
    AI_WORKER_USE_FAKE_SPEAKER_RUNTIME=false \
    AI_WORKER_BGE_M3_MODELS_DIR=... (etc.) \
    uv run --extra real-models pytest -m real_models -q
"""

from __future__ import annotations

import math
import struct
import wave
from importlib import util
from pathlib import Path

import pytest

from ai_worker.common.config import settings
from ai_worker.model_runtime import registry
from ai_worker.pipeline.asr.runtime import AsrRuntimeError
from ai_worker.pipeline.audio.preprocess import AudioMetadata
from ai_worker.pipeline.diarization.runtime import DiarizationRuntimeError, SpeakerTurn
from ai_worker.pipeline.speaker.runtime import SpeakerEmbeddingRuntimeError

pytestmark = [pytest.mark.real_models, pytest.mark.asyncio]


def _importable(name: str) -> bool:
    try:
        return util.find_spec(name) is not None
    except (ImportError, ModuleNotFoundError, ValueError):
        return False


def _require(runtime, package: str, models_dir: str | None) -> None:
    if runtime.use_fake:
        pytest.skip("runtime is in fake mode; set the matching AI_WORKER_USE_FAKE_*_RUNTIME=false")
    if not _importable(package):
        pytest.skip(f"{package} not installed (uv sync --extra real-models)")
    if not models_dir or not Path(models_dir).exists():
        pytest.skip(f"weights dir not staged ({models_dir!r})")


def _write_tone_wav(path: Path, *, seconds: float = 2.0, rate: int = 16000) -> None:
    """Write a short 16 kHz mono sine so audio runtimes have valid input."""
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        frames = bytearray()
        for i in range(int(seconds * rate)):
            sample = int(0.3 * 32767 * math.sin(2 * math.pi * 440 * (i / rate)))
            frames += struct.pack("<h", sample)
        wav.writeframes(bytes(frames))


def _tone_metadata(seconds: float = 2.0, rate: int = 16000) -> AudioMetadata:
    return AudioMetadata(
        duration_ms=int(seconds * 1000),
        sample_rate_hz=rate,
        channels=1,
        codec="pcm_s16le",
        bitrate=rate * 16,
        format_name="wav",
    )


@pytest.fixture(autouse=True)
def _fresh_registry():
    registry.reset_for_tests()
    yield
    registry.reset_for_tests()


async def test_bge_m3_loads_and_embeds() -> None:
    runtime = registry.get_bge_m3()
    _require(runtime, "FlagEmbedding", settings.bge_m3_models_dir)

    await runtime.ensure_loaded()
    assert runtime.status == "READY"

    vectors = await runtime.aembed(["你好，世界", "hello world"])
    assert len(vectors) == 2
    assert len(vectors[0]) == runtime.DIMENSION == 1024


async def test_bge_reranker_loads_and_ranks() -> None:
    runtime = registry.get_bge_reranker()
    _require(runtime, "FlagEmbedding", settings.bge_reranker_models_dir)

    await runtime.ensure_loaded()
    assert runtime.status == "READY"

    scores = await runtime.arank("天气怎么样", ["今天下雨", "股票上涨"])
    assert len(scores) == 2
    assert all(isinstance(s, float) for s in scores)


async def test_qwen3_asr_loads(tmp_path: Path) -> None:
    runtime = registry.get_asr_runtime()
    _require(runtime, "funasr", settings.qwen3_asr_models_dir)

    # Loading is the integration risk (funasr + trust_remote_code + weight layout).
    await runtime.ensure_loaded()
    assert runtime.status == "READY"

    wav = tmp_path / "tone.wav"
    _write_tone_wav(wav)
    try:
        segments = await runtime.transcribe(wav, _tone_metadata(), "zh")
        assert isinstance(segments, list)
    except AsrRuntimeError as exc:
        # A synthetic tone may legitimately transcribe to nothing.
        assert exc.error_code == "ASR_EMPTY_RESULT"


async def test_pyannote_diarization_loads(tmp_path: Path) -> None:
    runtime = registry.get_diarization_runtime()
    _require(runtime, "pyannote.audio", settings.pyannote_models_dir)

    await runtime.ensure_loaded()
    assert runtime.status == "READY"

    wav = tmp_path / "tone.wav"
    _write_tone_wav(wav)
    try:
        turns = await runtime.diarize(wav, _tone_metadata(), min_speakers=1, max_speakers=2)
        assert isinstance(turns, list)
        assert all(isinstance(t, SpeakerTurn) for t in turns)
    except DiarizationRuntimeError as exc:
        assert exc.error_code == "DIARIZATION_EMPTY_TURNS"


async def test_cam_plus_speaker_loads_and_embeds(tmp_path: Path) -> None:
    runtime = registry.get_speaker_runtime()
    _require(runtime, "modelscope", settings.cam_plus_models_dir)

    await runtime.ensure_loaded()
    assert runtime.status == "READY"

    wav = tmp_path / "tone.wav"
    _write_tone_wav(wav)
    turn = SpeakerTurn(speaker_label="SPEAKER_00", start_ms=0, end_ms=2000, confidence=1.0)
    try:
        embedding = await runtime.embed(wav, _tone_metadata(), turn)
        assert embedding.dimension == runtime.EMBEDDING_DIM == 192
        assert len(embedding.values) == 192
    except SpeakerEmbeddingRuntimeError:
        # Surface only as a real failure when weights are actually staged; the
        # _require guard above already skipped the no-weights case.
        raise
