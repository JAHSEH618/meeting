"""Build the speaker-candidates callback payload from runtime objects.

This helper takes a SpeakerEmbedding (plaintext) and matched candidates and
produces the JSON-serializable shape Java expects on
POST /internal/processing-tasks/{taskId}/speaker-candidates.

PLAINTEXT BOUNDARY: The returned dict still carries embedding.values. After
the callback succeeds, callers must clear the SpeakerEmbedding.values list
(see clear_embedding_values in the same module) — that is the only path on
which plaintext is allowed to travel.
"""

from __future__ import annotations

from ai_worker.pipeline.speaker.matcher import SpeakerMatchResult
from ai_worker.pipeline.speaker.runtime import SpeakerEmbedding


def build_speaker_candidate_entry(
    embedding: SpeakerEmbedding,
    match: SpeakerMatchResult,
) -> dict:
    if embedding.speaker_label != match.speaker_label:
        raise ValueError(
            f"speaker label mismatch between embedding ({embedding.speaker_label})"
            f" and match result ({match.speaker_label})"
        )
    return {
        "speakerLabel": embedding.speaker_label,
        "candidates": [
            {
                "personId": c.person_id,
                "speakerProfileId": c.speaker_profile_id,
                "confidence": c.confidence,
                "matchStatus": c.match_status,
            }
            for c in match.candidates
        ],
        "embedding": {
            "format": "FLOAT32_ARRAY",
            "dimension": embedding.dimension,
            "values": list(embedding.values),
            "checksum": embedding.checksum,
            "modelVersion": embedding.model_version,
            "plaintextTransport": "INTERNAL_TLS_HMAC_CALLBACK",
            "persistedBy": "MEETING_API_KMS_ENVELOPE_ENCRYPTION",
        },
    }


def clear_embedding_values(embedding: SpeakerEmbedding) -> None:
    """Overwrite the plaintext values list in place.

    Python lists are reference-counted, so this is best-effort: any other holder of
    the same list reference will still observe the zeroed values. The intent is to
    flush the working buffer so retries do not surface stale data and so external
    inspection (e.g. core dumps) cannot recover useful information.
    """

    if not embedding.values:
        return
    for i in range(len(embedding.values)):
        embedding.values[i] = 0.0
