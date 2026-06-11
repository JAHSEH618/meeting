from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import pytest

from ai_worker.infrastructure.java_callback.client import CallbackResponse
from ai_worker.pipeline.speaker.matcher import SpeakerMatchCandidate, SpeakerMatchResult
from ai_worker.pipeline.speaker.runtime import SpeakerEmbedding
from ai_worker.pipeline.speaker.submit import (
    SpeakerCandidateSubmission,
    submit_and_clear_speaker_enrollment_embedding,
    submit_and_clear_speaker_candidates,
)


@dataclass
class CapturingClient:
    last_payload: list[dict] | None = None
    raise_on_call: Exception | None = None
    next_response: CallbackResponse | None = None

    async def submit_speaker_candidates(
        self,
        *,
        task_id: str,
        tenant_id: str,
        attempt_no: int,
        speaker_candidates: list[dict],
        meeting_id: str | None = None,
        trace_id: str = "",
    ) -> CallbackResponse:
        # Snapshot the plaintext as observed at call time so we can verify the wire
        # payload was non-zero.
        self.last_payload = [_deep_copy(d) for d in speaker_candidates]
        if self.raise_on_call is not None:
            raise self.raise_on_call
        return self.next_response or CallbackResponse(http_status=200, accepted=True)


@dataclass
class CapturingEnrollmentClient:
    last_payload: dict | None = None
    raise_on_call: Exception | None = None
    next_response: CallbackResponse | None = None

    async def submit_speaker_enrollment_embedding(
        self,
        *,
        task_id: str,
        tenant_id: str,
        attempt_no: int,
        speaker_profile_id: str,
        speaker_enrollment_id: str,
        audio_file_id: str,
        embedding: dict,
        trace_id: str = "",
    ) -> CallbackResponse:
        self.last_payload = _deep_copy(embedding)
        if self.raise_on_call is not None:
            raise self.raise_on_call
        return self.next_response or CallbackResponse(http_status=200, accepted=True)


def _deep_copy(d: dict[str, Any]) -> dict[str, Any]:
    import copy

    return copy.deepcopy(d)


def _embedding(values: list[float], label: str = "SPEAKER_00") -> SpeakerEmbedding:
    return SpeakerEmbedding(
        speaker_label=label,
        values=values,
        dimension=len(values),
        model_version="deterministic-speaker-v0",
        checksum="x" * 64,
        quality_score=0.7,
    )


def _match(label: str = "SPEAKER_00") -> SpeakerMatchResult:
    return SpeakerMatchResult(
        speaker_label=label,
        candidates=[
            SpeakerMatchCandidate("alice", "spk_alice", 0.85, "CANDIDATE"),
        ],
    )


@pytest.mark.asyncio
async def test_callback_succeeds_and_clears_plaintext_in_finally() -> None:
    embedding = _embedding([0.1, 0.2, 0.3])
    client = CapturingClient()
    submission = SpeakerCandidateSubmission(embedding=embedding, match=_match())

    response = await submit_and_clear_speaker_candidates(
        client,  # type: ignore[arg-type]
        task_id="task_01",
        tenant_id="tenant_01",
        attempt_no=1,
        submissions=[submission],
        meeting_id="meeting_01",
    )

    assert response.accepted is True
    assert client.last_payload is not None
    assert client.last_payload[0]["embedding"]["values"] == [0.1, 0.2, 0.3]
    # After the call, the in-process buffer is zeroed.
    assert embedding.values == [0.0, 0.0, 0.0]


@pytest.mark.asyncio
async def test_callback_raises_still_clears_plaintext() -> None:
    embedding = _embedding([0.4, 0.5])
    client = CapturingClient(raise_on_call=RuntimeError("boom"))
    submission = SpeakerCandidateSubmission(embedding=embedding, match=_match())

    with pytest.raises(RuntimeError):
        await submit_and_clear_speaker_candidates(
            client,  # type: ignore[arg-type]
            task_id="task_01",
            tenant_id="tenant_01",
            attempt_no=1,
            submissions=[submission],
        )

    assert embedding.values == [0.0, 0.0]


@pytest.mark.asyncio
async def test_callback_returns_failure_response_and_still_clears_plaintext() -> None:
    embedding = _embedding([0.7, 0.8, 0.9, 1.0])
    client = CapturingClient(next_response=CallbackResponse(http_status=503, accepted=False, error_code="WRITEBACK_FAILED"))
    submission = SpeakerCandidateSubmission(embedding=embedding, match=_match())

    response = await submit_and_clear_speaker_candidates(
        client,  # type: ignore[arg-type]
        task_id="task_01",
        tenant_id="tenant_01",
        attempt_no=2,
        submissions=[submission],
    )

    assert response.accepted is False
    assert response.error_code == "WRITEBACK_FAILED"
    assert embedding.values == [0.0, 0.0, 0.0, 0.0]


@pytest.mark.asyncio
async def test_multiple_submissions_all_cleared() -> None:
    a = _embedding([1.0, 2.0], "SPEAKER_00")
    b = _embedding([3.0, 4.0], "SPEAKER_01")
    client = CapturingClient()

    await submit_and_clear_speaker_candidates(
        client,  # type: ignore[arg-type]
        task_id="task_01",
        tenant_id="tenant_01",
        attempt_no=1,
        submissions=[
            SpeakerCandidateSubmission(a, _match("SPEAKER_00")),
            SpeakerCandidateSubmission(b, _match("SPEAKER_01")),
        ],
    )

    assert a.values == [0.0, 0.0]
    assert b.values == [0.0, 0.0]


@pytest.mark.asyncio
async def test_enrollment_callback_succeeds_and_clears_plaintext() -> None:
    embedding = _embedding([0.1, 0.2, 0.3])
    client = CapturingEnrollmentClient()

    response = await submit_and_clear_speaker_enrollment_embedding(
        client,  # type: ignore[arg-type]
        task_id="task_01",
        tenant_id="tenant_01",
        attempt_no=1,
        speaker_profile_id="sp_01",
        speaker_enrollment_id="se_01",
        audio_file_id="audio_01",
        embedding=embedding,
    )

    assert response.accepted is True
    assert client.last_payload is not None
    assert client.last_payload["values"] == [0.1, 0.2, 0.3]
    assert client.last_payload["qualityScore"] == 0.7
    assert embedding.values == [0.0, 0.0, 0.0]


@pytest.mark.asyncio
async def test_enrollment_callback_failure_response_still_clears_plaintext() -> None:
    embedding = _embedding([0.4, 0.5])
    client = CapturingEnrollmentClient(
        next_response=CallbackResponse(http_status=503, accepted=False, error_code="WRITEBACK_FAILED")
    )

    response = await submit_and_clear_speaker_enrollment_embedding(
        client,  # type: ignore[arg-type]
        task_id="task_01",
        tenant_id="tenant_01",
        attempt_no=1,
        speaker_profile_id="sp_01",
        speaker_enrollment_id="se_01",
        audio_file_id="audio_01",
        embedding=embedding,
    )

    assert response.accepted is False
    assert embedding.values == [0.0, 0.0]


@pytest.mark.asyncio
async def test_enrollment_callback_exception_still_clears_plaintext() -> None:
    embedding = _embedding([0.6, 0.7])
    client = CapturingEnrollmentClient(raise_on_call=RuntimeError("boom"))

    with pytest.raises(RuntimeError):
        await submit_and_clear_speaker_enrollment_embedding(
            client,  # type: ignore[arg-type]
            task_id="task_01",
            tenant_id="tenant_01",
            attempt_no=1,
            speaker_profile_id="sp_01",
            speaker_enrollment_id="se_01",
            audio_file_id="audio_01",
            embedding=embedding,
        )

    assert embedding.values == [0.0, 0.0]
