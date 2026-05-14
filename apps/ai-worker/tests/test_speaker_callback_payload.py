from __future__ import annotations

import pytest

from ai_worker.pipeline.speaker.callback_payload import (
    build_speaker_candidate_entry,
    clear_embedding_values,
)
from ai_worker.pipeline.speaker.matcher import SpeakerMatchCandidate, SpeakerMatchResult
from ai_worker.pipeline.speaker.runtime import SpeakerEmbedding


def _embedding(values: list[float], label: str = "SPEAKER_00") -> SpeakerEmbedding:
    return SpeakerEmbedding(
        speaker_label=label,
        values=values,
        dimension=len(values),
        model_version="deterministic-speaker-v0",
        checksum="a" * 64,
        quality_score=0.9,
    )


def _match(label: str = "SPEAKER_00") -> SpeakerMatchResult:
    return SpeakerMatchResult(
        speaker_label=label,
        candidates=[
            SpeakerMatchCandidate(
                person_id="alice",
                speaker_profile_id="spk_alice",
                confidence=0.92,
                match_status="CANDIDATE",
            )
        ],
    )


def test_payload_includes_plaintext_values_inline() -> None:
    embedding = _embedding([0.1, 0.2, 0.3, 0.4])
    payload = build_speaker_candidate_entry(embedding, _match())

    assert payload["speakerLabel"] == "SPEAKER_00"
    assert payload["embedding"]["format"] == "FLOAT32_ARRAY"
    assert payload["embedding"]["dimension"] == 4
    assert payload["embedding"]["values"] == [0.1, 0.2, 0.3, 0.4]
    assert payload["embedding"]["plaintextTransport"] == "INTERNAL_TLS_HMAC_CALLBACK"
    assert payload["embedding"]["persistedBy"] == "MEETING_API_KMS_ENVELOPE_ENCRYPTION"


def test_payload_includes_candidates() -> None:
    payload = build_speaker_candidate_entry(_embedding([0.0] * 4), _match())
    assert payload["candidates"][0]["personId"] == "alice"
    assert payload["candidates"][0]["confidence"] == pytest.approx(0.92)


def test_label_mismatch_raises_to_protect_against_misuse() -> None:
    with pytest.raises(ValueError):
        build_speaker_candidate_entry(_embedding([0.0], "SPEAKER_00"), _match("SPEAKER_01"))


def test_clear_embedding_values_zeroizes_inplace() -> None:
    embedding = _embedding([0.1, 0.2, 0.3])
    clear_embedding_values(embedding)
    assert embedding.values == [0.0, 0.0, 0.0]


def test_clear_embedding_values_handles_empty_buffer() -> None:
    embedding = _embedding([])
    clear_embedding_values(embedding)
    assert embedding.values == []
