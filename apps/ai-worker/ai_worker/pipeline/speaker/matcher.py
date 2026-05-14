"""Speaker matching pipeline.

Matches a meeting speaker's embedding to known person identities, but ONLY against
the Java-authorized scope passed in the task message ({TaskMessage.known_participants}
plus any directly bound {speaker_profile_id}). The runtime must never search the
entire tenant — the matching scope is decided by Java's permission layer at task
creation time.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Protocol, runtime_checkable

from ai_worker.domain.task import TaskMessage
from ai_worker.pipeline.speaker.runtime import SpeakerEmbedding


@dataclass(frozen=True)
class SpeakerMatchCandidate:
    person_id: str
    speaker_profile_id: str
    confidence: float
    match_status: str


@dataclass(frozen=True)
class SpeakerMatchResult:
    speaker_label: str
    candidates: list[SpeakerMatchCandidate]


@runtime_checkable
class SpeakerMatcher(Protocol):
    async def match(
        self,
        task: TaskMessage,
        embedding: SpeakerEmbedding,
    ) -> SpeakerMatchResult:
        ...


def cosine_similarity(a: list[float], b: list[float]) -> float:
    if len(a) != len(b) or not a:
        return 0.0
    dot = 0.0
    norm_a = 0.0
    norm_b = 0.0
    for x, y in zip(a, b):
        dot += x * y
        norm_a += x * x
        norm_b += y * y
    if norm_a == 0.0 or norm_b == 0.0:
        return 0.0
    return dot / (math.sqrt(norm_a) * math.sqrt(norm_b))


class AuthorizedScopeMatcher:
    """Match against a synthetic reference embedding for every authorized participant.

    For deterministic tests, the reference embedding for a participant id is derived
    from the same hashing rules as DeterministicSpeakerEmbeddingRuntime, keyed by the
    participant id. In production this is replaced by a profile centroid lookup that
    only loads embeddings authorized for the task.
    """

    DEFAULT_MIN_CONFIDENCE = 0.35
    DEFAULT_TOP_K = 5

    def __init__(
        self,
        reference_supplier: "ReferenceEmbeddingSupplier | None" = None,
        min_confidence: float = DEFAULT_MIN_CONFIDENCE,
        top_k: int = DEFAULT_TOP_K,
    ) -> None:
        self._reference_supplier = reference_supplier or DeterministicReferenceSupplier()
        self._min_confidence = min_confidence
        self._top_k = top_k

    async def match(
        self,
        task: TaskMessage,
        embedding: SpeakerEmbedding,
    ) -> SpeakerMatchResult:
        scope = _authorized_scope(task)
        if not scope:
            return SpeakerMatchResult(speaker_label=embedding.speaker_label, candidates=[])

        scored: list[SpeakerMatchCandidate] = []
        for participant_id in scope:
            reference = await self._reference_supplier.reference_embedding(
                tenant_id=task.tenant_id,
                participant_id=participant_id,
                dimension=embedding.dimension,
            )
            confidence = cosine_similarity(embedding.values, reference)
            if confidence < self._min_confidence:
                continue
            scored.append(
                SpeakerMatchCandidate(
                    person_id=participant_id,
                    speaker_profile_id=_synthetic_profile_id(participant_id),
                    confidence=confidence,
                    match_status="CANDIDATE",
                )
            )
        scored.sort(key=lambda c: c.confidence, reverse=True)
        return SpeakerMatchResult(
            speaker_label=embedding.speaker_label,
            candidates=scored[: self._top_k],
        )


@runtime_checkable
class ReferenceEmbeddingSupplier(Protocol):
    async def reference_embedding(
        self,
        tenant_id: str,
        participant_id: str,
        dimension: int,
    ) -> list[float]:
        ...


class DeterministicReferenceSupplier:
    """Synthesizes a reference embedding from the participant id for local smoke tests."""

    async def reference_embedding(
        self,
        tenant_id: str,
        participant_id: str,
        dimension: int,
    ) -> list[float]:
        import hashlib

        digest = hashlib.sha256(f"{tenant_id}|{participant_id}".encode("utf-8")).digest()
        floats: list[float] = []
        for i in range(dimension):
            byte = digest[i % len(digest)]
            mix = ((byte * 31) ^ (i * 7)) & 0xFF
            floats.append((mix - 128) / 128.0)
        norm = math.sqrt(sum(f * f for f in floats)) or 1.0
        return [f / norm for f in floats]


def _authorized_scope(task: TaskMessage) -> list[str]:
    """Combine knownParticipants and speaker_profile_id into a unique authorized list."""

    scope: list[str] = []
    seen: set[str] = set()
    for participant in task.known_participants or []:
        if participant and participant not in seen:
            seen.add(participant)
            scope.append(participant)
    if task.speaker_profile_id and task.speaker_profile_id not in seen:
        seen.add(task.speaker_profile_id)
        scope.append(task.speaker_profile_id)
    return scope


def _synthetic_profile_id(participant_id: str) -> str:
    if participant_id.startswith("spk_"):
        return participant_id
    return f"spk_{participant_id}"
