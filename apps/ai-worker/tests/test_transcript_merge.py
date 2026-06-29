from __future__ import annotations

from ai_worker.domain.task import TaskMessage
from ai_worker.pipeline.alignment.transcript_merge import merge_transcript_segments
from ai_worker.pipeline.asr.runtime import AsrSegment
from ai_worker.pipeline.diarization.runtime import SpeakerTurn


def _task() -> TaskMessage:
    return TaskMessage(
        task_id="task_merge_01",
        task_type="MEETING_FULL_PIPELINE",
        tenant_id="tenant_01",
        attempt_no=1,
        pipeline_steps=("TRANSCRIPT_MERGE",),
        trace_id="trace_01",
    )


def test_segment_assigned_to_max_overlap_turn_not_first() -> None:
    # Two overlapping turns; the segment overlaps the SECOND turn far more.
    # Midpoint-containment used to pick whichever turn appeared first.
    segment = AsrSegment(start_ms=1000, end_ms=2000, text="hi", confidence=0.9)
    turns = [
        SpeakerTurn(speaker_label="SPEAKER_00", start_ms=0, end_ms=1100, confidence=0.8),
        SpeakerTurn(speaker_label="SPEAKER_01", start_ms=1050, end_ms=3000, confidence=0.8),
    ]

    result = merge_transcript_segments(_task(), [segment], turns)

    assert result[0]["speakerLabel"] == "SPEAKER_01"


def test_segment_in_gap_falls_back_to_nearest_turn_not_first() -> None:
    # Segment sits in a gap after the second turn; nearest is SPEAKER_01, not
    # the first turn in the list.
    segment = AsrSegment(start_ms=5000, end_ms=5500, text="late", confidence=0.9)
    turns = [
        SpeakerTurn(speaker_label="SPEAKER_00", start_ms=0, end_ms=1000, confidence=0.8),
        SpeakerTurn(speaker_label="SPEAKER_01", start_ms=4000, end_ms=4800, confidence=0.8),
    ]

    result = merge_transcript_segments(_task(), [segment], turns)

    assert result[0]["speakerLabel"] == "SPEAKER_01"


def test_no_turns_defaults_to_speaker_00() -> None:
    segment = AsrSegment(start_ms=0, end_ms=500, text="solo", confidence=0.9)

    result = merge_transcript_segments(_task(), [segment], [])

    assert result[0]["speakerLabel"] == "SPEAKER_00"
