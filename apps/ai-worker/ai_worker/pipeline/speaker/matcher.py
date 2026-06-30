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


@dataclass(frozen=True)
class ReferenceEmbedding:
    person_id: str
    speaker_profile_id: str
    values: list[float]


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

        # Resolve the WHOLE authorized scope in one batched call rather than one
        # HTTP round-trip per participant. The production supplier batches +
        # caches by the full id-set, so even though match() runs per speaker the
        # reference fetch collapses to a single request per task.
        references = await self._resolve_scope(task.tenant_id, scope, embedding.dimension)

        scored: list[SpeakerMatchCandidate] = []
        for participant_id in scope:
            reference = references.get(participant_id)
            if reference is None:
                # Participant has no usable reference (un-enrolled / revoked /
                # omitted by Java). Skip — do not fail the whole match.
                continue
            confidence = cosine_similarity(embedding.values, reference.values)
            if confidence < self._min_confidence:
                continue
            scored.append(
                SpeakerMatchCandidate(
                    person_id=reference.person_id,
                    speaker_profile_id=reference.speaker_profile_id,
                    confidence=confidence,
                    match_status="CANDIDATE",
                )
            )
        scored.sort(key=lambda c: c.confidence, reverse=True)
        return SpeakerMatchResult(
            speaker_label=embedding.speaker_label,
            candidates=scored[: self._top_k],
        )

    async def _resolve_scope(
        self, tenant_id: str, scope: list[str], dimension: int
    ) -> dict[str, "ReferenceEmbedding"]:
        """Resolve every authorized participant's reference embedding.

        Prefers the supplier's batched ``reference_embeddings`` (one HTTP call);
        falls back to per-id ``reference_embedding`` for simple/local suppliers
        that don't implement the batch method.
        """
        batched = getattr(self._reference_supplier, "reference_embeddings", None)
        if batched is not None:
            return await batched(tenant_id, scope, dimension)
        out: dict[str, ReferenceEmbedding] = {}
        for participant_id in scope:
            out[participant_id] = await self._reference_supplier.reference_embedding(
                tenant_id=tenant_id,
                participant_id=participant_id,
                dimension=dimension,
            )
        return out


@runtime_checkable
class ReferenceEmbeddingSupplier(Protocol):
    async def reference_embedding(
        self,
        tenant_id: str,
        participant_id: str,
        dimension: int,
    ) -> ReferenceEmbedding:
        ...


class DeterministicReferenceSupplier:
    """Synthesizes a reference embedding from the participant id for local smoke tests."""

    async def reference_embedding(
        self,
        tenant_id: str,
        participant_id: str,
        dimension: int,
    ) -> ReferenceEmbedding:
        import hashlib

        digest = hashlib.sha256(f"{tenant_id}|{participant_id}".encode("utf-8")).digest()
        floats: list[float] = []
        for i in range(dimension):
            byte = digest[i % len(digest)]
            mix = ((byte * 31) ^ (i * 7)) & 0xFF
            floats.append((mix - 128) / 128.0)
        norm = math.sqrt(sum(f * f for f in floats)) or 1.0
        values = [f / norm for f in floats]
        return ReferenceEmbedding(
            person_id=participant_id,
            speaker_profile_id=participant_id,
            values=values,
        )


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
