from __future__ import annotations

from dataclasses import replace
from typing import Any

import pytest

from ai_worker.domain.task import TaskMessage
from ai_worker.pipeline.speaker.matcher import (
    AuthorizedScopeMatcher,
    DeterministicReferenceSupplier,
    SpeakerMatchResult,
    cosine_similarity,
)
from ai_worker.pipeline.speaker.runtime import SpeakerEmbedding


def _embedding(values: list[float], label: str = "SPEAKER_00") -> SpeakerEmbedding:
    return SpeakerEmbedding(
        speaker_label=label,
        values=values,
        dimension=len(values),
        model_version="deterministic-speaker-v0",
        checksum="x" * 64,
        quality_score=0.8,
    )


def _task(known_participants: list[str], speaker_profile_id: str | None = None) -> TaskMessage:
    payload: dict[str, Any] = {
        "task_id": "task_01",
        "task_type": "MEETING_FULL_PIPELINE",
        "tenant_id": "tenant_01",
        "security_level": "INTERNAL",
        "attempt_no": 1,
        "pipeline_steps": ("SPEAKER_EMBEDDING", "SPEAKER_MATCHING"),
        "known_participants": list(known_participants),
        "speaker_profile_id": speaker_profile_id,
        "meeting_id": "meeting_01",
    }
    return TaskMessage(**payload)


@pytest.mark.asyncio
async def test_empty_scope_returns_no_candidates() -> None:
    matcher = AuthorizedScopeMatcher()
    result = await matcher.match(_task([]), _embedding([0.1, 0.2, 0.3]))
    assert result.candidates == []
    assert result.speaker_label == "SPEAKER_00"


@pytest.mark.asyncio
async def test_matches_only_against_authorized_known_participants() -> None:
    matcher = AuthorizedScopeMatcher(min_confidence=-1.0)  # accept all so we can inspect filtering
    reference = await DeterministicReferenceSupplier().reference_embedding(
        tenant_id="tenant_01", participant_id="alice", dimension=192
    )
    embedding = _embedding(reference)

    result = await matcher.match(_task(["alice", "bob"]), embedding)

    assert {c.person_id for c in result.candidates} == {"alice", "bob"}
    assert all(c.match_status == "CANDIDATE" for c in result.candidates)
    # alice should be top because the embedding equals her reference
    assert result.candidates[0].person_id == "alice"


@pytest.mark.asyncio
async def test_speaker_enrollment_task_uses_profile_id_as_scope() -> None:
    matcher = AuthorizedScopeMatcher(min_confidence=-1.0)
    reference = await DeterministicReferenceSupplier().reference_embedding(
        tenant_id="tenant_01", participant_id="spk_alice", dimension=192
    )
    embedding = _embedding(reference)

    result = await matcher.match(_task([], speaker_profile_id="spk_alice"), embedding)

    assert len(result.candidates) == 1
    assert result.candidates[0].person_id == "spk_alice"
    assert result.candidates[0].speaker_profile_id == "spk_alice"


@pytest.mark.asyncio
async def test_below_min_confidence_is_filtered() -> None:
    matcher = AuthorizedScopeMatcher(min_confidence=0.99)
    embedding = _embedding([0.5, 0.5, 0.5, 0.5])  # very unlikely to match closely

    result = await matcher.match(_task(["alice", "bob", "carol"]), embedding)

    assert result.candidates == [] or all(c.confidence >= 0.99 for c in result.candidates)


def test_cosine_similarity_orthogonal_vectors_score_zero() -> None:
    assert cosine_similarity([1.0, 0.0], [0.0, 1.0]) == 0.0
    assert cosine_similarity([1.0, 1.0], [1.0, 1.0]) == pytest.approx(1.0)


def test_cosine_similarity_mismatched_dimensions_score_zero() -> None:
    assert cosine_similarity([1.0, 2.0, 3.0], [1.0, 2.0]) == 0.0


@pytest.mark.asyncio
async def test_top_k_caps_result_size() -> None:
    matcher = AuthorizedScopeMatcher(min_confidence=-1.0, top_k=2)
    reference = await DeterministicReferenceSupplier().reference_embedding(
        tenant_id="tenant_01", participant_id="alice", dimension=192
    )

    result: SpeakerMatchResult = await matcher.match(
        _task(["alice", "bob", "carol", "dave"]),
        _embedding(reference),
    )
    assert len(result.candidates) <= 2


@pytest.mark.asyncio
async def test_replace_pattern_is_safe_on_task() -> None:
    # ensure we can use replace() to build modified task copies without mutating
    base = _task(["alice"])
    different_scope = replace(base, known_participants=["bob"])
    matcher = AuthorizedScopeMatcher(min_confidence=-1.0)
    result = await matcher.match(different_scope, _embedding([0.1] * 192))
    assert {c.person_id for c in result.candidates} == {"bob"}
