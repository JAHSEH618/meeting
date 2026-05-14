from __future__ import annotations

from typing import Any

from ai_worker.domain.task import TaskMessage
from ai_worker.pipeline.asr.runtime import AsrSegment
from ai_worker.pipeline.diarization.runtime import SpeakerTurn


def merge_transcript_segments(
    task: TaskMessage,
    asr_segments: list[AsrSegment],
    speaker_turns: list[SpeakerTurn],
) -> list[dict[str, Any]]:
    segments: list[dict[str, Any]] = []
    for index, segment in enumerate(asr_segments, start=1):
        turn = _speaker_for_segment(segment, speaker_turns)
        segments.append(
            {
                "segmentId": f"{task.task_id}_seg_{index:04d}",
                "startMs": segment.start_ms,
                "endMs": segment.end_ms,
                "speakerLabel": turn.speaker_label if turn else "SPEAKER_00",
                "text": segment.text,
                "asrConfidence": segment.confidence,
                "diarizationConfidence": turn.confidence if turn else 0.0,
                "speakerConfidence": 0.0,
                "timestampPrecision": "SEGMENT",
            }
        )
    return segments


def _speaker_for_segment(segment: AsrSegment, speaker_turns: list[SpeakerTurn]) -> SpeakerTurn | None:
    if not speaker_turns:
        return None
    midpoint = segment.start_ms + max(0, segment.end_ms - segment.start_ms) // 2
    for turn in speaker_turns:
        if turn.start_ms <= midpoint <= turn.end_ms:
            return turn
    return speaker_turns[0]
