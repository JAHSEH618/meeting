from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, runtime_checkable

from ai_worker.pipeline.audio.preprocess import AudioMetadata


class DiarizationRuntimeError(Exception):
    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code


@dataclass(frozen=True)
class SpeakerTurn:
    speaker_label: str
    start_ms: int
    end_ms: int
    confidence: float


@runtime_checkable
class DiarizationRuntime(Protocol):
    async def diarize(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        *,
        min_speakers: int | None = None,
        max_speakers: int | None = None,
    ) -> list[SpeakerTurn]:
        ...


class SingleSpeakerDiarizationRuntime:
    model_version = "single-speaker-v0"

    async def diarize(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        *,
        min_speakers: int | None = None,
        max_speakers: int | None = None,
    ) -> list[SpeakerTurn]:
        # Single-speaker fallback ignores the speaker-count bounds.
        if metadata.duration_ms <= 0:
            raise DiarizationRuntimeError("DIARIZATION_EMPTY_TURNS", "audio duration is empty")
        return [
            SpeakerTurn(
                speaker_label="SPEAKER_00",
                start_ms=0,
                end_ms=max(1, metadata.duration_ms),
                confidence=0.8,
            )
        ]
