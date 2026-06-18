"""Speaker embedding model runtime.

A small, deterministic placeholder that produces a stable 192-dim embedding
keyed off the speaker label + audio path. Real deployments use the registry-
managed CAM++ runtime from ``ai_worker.model_runtime.speaker``.

The runtime is intentionally cheap so smoke pipelines and tests can exercise
the full speaker-candidates callback path without a GPU.
"""

from __future__ import annotations

import hashlib
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, runtime_checkable

from ai_worker.pipeline.audio.preprocess import AudioMetadata
from ai_worker.pipeline.diarization.runtime import SpeakerTurn


class SpeakerEmbeddingRuntimeError(Exception):
    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code


@dataclass(frozen=True)
class SpeakerEmbedding:
    """Plaintext speaker embedding ready for callback transmission.

    The caller is responsible for clearing the values list as soon as the
    callback has been acknowledged (or retries exhausted). The runtime never
    persists this object anywhere.
    """

    speaker_label: str
    values: list[float]
    dimension: int
    model_version: str
    checksum: str
    quality_score: float


@runtime_checkable
class SpeakerEmbeddingRuntime(Protocol):
    async def embed(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        speaker_turn: SpeakerTurn,
    ) -> SpeakerEmbedding:
        ...


class DeterministicSpeakerEmbeddingRuntime:
    """Deterministic embedding derived from a SHA-256 seed.

    Same (audio path, speaker label) always yields the same vector, so the
    smoke pipeline produces reproducible candidates. Real GPU runtimes drop
    in by implementing the {SpeakerEmbeddingRuntime} protocol.
    """

    model_version = "deterministic-speaker-v0"
    dimension = 192

    async def embed(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        speaker_turn: SpeakerTurn,
    ) -> SpeakerEmbedding:
        if metadata.duration_ms <= 0:
            raise SpeakerEmbeddingRuntimeError(
                "SPEAKER_EMBEDDING_FAILED", "audio duration must be positive"
            )
        if speaker_turn.end_ms <= speaker_turn.start_ms:
            raise SpeakerEmbeddingRuntimeError(
                "SPEAKER_EMBEDDING_FAILED",
                f"invalid speaker turn boundaries for {speaker_turn.speaker_label}",
            )
        seed_material = f"{audio_path}|{speaker_turn.speaker_label}|{speaker_turn.start_ms}|{speaker_turn.end_ms}"
        digest = hashlib.sha256(seed_material.encode("utf-8")).digest()
        # Expand the 32-byte digest into a 192-dim L2-normalized vector.
        floats: list[float] = []
        for i in range(self.dimension):
            byte = digest[i % len(digest)]
            mix = ((byte * 31) ^ (i * 7)) & 0xFF
            floats.append((mix - 128) / 128.0)
        norm = math.sqrt(sum(f * f for f in floats)) or 1.0
        normalized = [f / norm for f in floats]
        checksum = hashlib.sha256(
            ",".join(f"{v:.6f}" for v in normalized).encode("utf-8")
        ).hexdigest()
        return SpeakerEmbedding(
            speaker_label=speaker_turn.speaker_label,
            values=normalized,
            dimension=self.dimension,
            model_version=self.model_version,
            checksum=checksum,
            quality_score=float(speaker_turn.confidence),
        )
