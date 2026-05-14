from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, runtime_checkable

from ai_worker.pipeline.audio.preprocess import AudioMetadata


class AsrRuntimeError(Exception):
    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code


@dataclass(frozen=True)
class AsrSegment:
    start_ms: int
    end_ms: int
    text: str
    confidence: float


@runtime_checkable
class AsrModelRuntime(Protocol):
    async def transcribe(self, audio_path: Path, metadata: AudioMetadata, language: str | None) -> list[AsrSegment]:
        ...


class DeterministicAsrRuntime:
    """Local ASR placeholder behind the real runtime port.

    It does not call any third-party service. The class gives the pipeline a
    stable contract and callback shape until an on-device model runtime is wired.
    """

    model_version = "deterministic-asr-v0"

    async def transcribe(self, audio_path: Path, metadata: AudioMetadata, language: str | None) -> list[AsrSegment]:
        if metadata.duration_ms <= 0:
            raise AsrRuntimeError("ASR_EMPTY_RESULT", "audio duration is empty")
        text = _sidecar_text(audio_path) or _default_text(language)
        return [
            AsrSegment(
                start_ms=0,
                end_ms=max(1, metadata.duration_ms),
                text=text,
                confidence=0.6,
            )
        ]


def _sidecar_text(audio_path: Path) -> str | None:
    sidecar = audio_path.with_suffix(audio_path.suffix + ".txt")
    if not sidecar.exists():
        return None
    text = sidecar.read_text(encoding="utf-8").strip()
    return text or None


def _default_text(language: str | None) -> str:
    if language and language.lower().startswith("zh"):
        return "本地音频已完成转录。"
    return "Local audio transcription completed."
