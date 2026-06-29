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
    """Assign the diarization turn that best matches an ASR segment.

    Picks the turn with the largest temporal overlap with the segment (ties
    broken by earlier start). Midpoint-containment (the previous approach)
    mis-assigned overlapping/cross-talk turns and silently blamed turn[0] for
    any segment falling in a gap. When nothing overlaps (segment in a gap or
    spanning a boundary) we fall back to the nearest turn by time rather than
    the first one in the list.
    """
    if not speaker_turns:
        return None
    seg_start = segment.start_ms
    seg_end = max(segment.start_ms, segment.end_ms)

    best_turn: SpeakerTurn | None = None
    best_overlap = 0
    for turn in speaker_turns:
        overlap = min(seg_end, turn.end_ms) - max(seg_start, turn.start_ms)
        if overlap > best_overlap:
            best_overlap = overlap
            best_turn = turn
    if best_turn is not None:
        return best_turn

    def _gap(turn: SpeakerTurn) -> int:
        if seg_end <= turn.start_ms:
            return turn.start_ms - seg_end
        if seg_start >= turn.end_ms:
            return seg_start - turn.end_ms
        return 0

    return min(speaker_turns, key=_gap)
