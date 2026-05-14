"""High-level orchestrator that submits speaker candidates and ALWAYS clears plaintext.

This wrapper enforces the invariant that the plaintext embedding lives only as long
as the callback attempt. Whether the callback succeeds, fails with a non-retryable
error, or exhausts its own retry budget, the SpeakerEmbedding.values buffer is
zeroized in a finally block before this function returns.

The wrapper does NOT retry on its own — the underlying JavaCallbackClient already
runs a bounded retry loop. We only guarantee one thing on top: zeroization.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from ai_worker.infrastructure.java_callback.client import CallbackResponse, JavaCallbackClient
from ai_worker.pipeline.speaker.callback_payload import (
    build_speaker_candidate_entry,
    clear_embedding_values,
)
from ai_worker.pipeline.speaker.matcher import SpeakerMatchResult
from ai_worker.pipeline.speaker.runtime import SpeakerEmbedding

logger = logging.getLogger(__name__)


@dataclass
class SpeakerCandidateSubmission:
    """Pair an embedding with its matched candidates for a single speaker label."""

    embedding: SpeakerEmbedding
    match: SpeakerMatchResult


async def submit_and_clear_speaker_candidates(
    client: JavaCallbackClient,
    task_id: str,
    tenant_id: str,
    attempt_no: int,
    submissions: list[SpeakerCandidateSubmission],
    meeting_id: str | None = None,
    trace_id: str = "",
) -> CallbackResponse:
    """Submit a single speaker-candidates callback and clear all plaintext buffers.

    Returns the underlying CallbackResponse so callers can react to non-retryable
    failures (e.g. log WRITEBACK_FAILED). The plaintext clear runs regardless of
    response status.
    """

    payload = [
        build_speaker_candidate_entry(s.embedding, s.match) for s in submissions
    ]
    try:
        response = await client.submit_speaker_candidates(
            task_id=task_id,
            tenant_id=tenant_id,
            attempt_no=attempt_no,
            speaker_candidates=payload,
            meeting_id=meeting_id,
            trace_id=trace_id,
        )
        return response
    finally:
        for submission in submissions:
            clear_embedding_values(submission.embedding)
        # The payload list also held references to the plaintext arrays via list(values).
        # Overwrite each payload's embedding.values so any lingering reference is wiped.
        for entry in payload:
            embedding_dict = entry.get("embedding")
            if isinstance(embedding_dict, dict):
                values = embedding_dict.get("values")
                if isinstance(values, list):
                    for i in range(len(values)):
                        values[i] = 0.0
        logger.debug(
            "speaker_candidates_plaintext_cleared task=%s submissions=%d",
            task_id,
            len(submissions),
        )
