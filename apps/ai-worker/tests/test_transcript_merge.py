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


def test_equal_overlap_tie_breaks_by_original_list_order() -> None:
    # Two turns overlap the segment by exactly the same amount; the winner is
    # the one appearing FIRST in the (unsorted) diarization list, even though
    # it starts later — the bisect index must preserve this tie-break.
    segment = AsrSegment(start_ms=1000, end_ms=2000, text="tie", confidence=0.9)
    turns = [
        SpeakerTurn(speaker_label="SPEAKER_01", start_ms=1500, end_ms=2500, confidence=0.8),
        SpeakerTurn(speaker_label="SPEAKER_00", start_ms=500, end_ms=1500, confidence=0.8),
    ]

    result = merge_transcript_segments(_task(), [segment], turns)

    assert result[0]["speakerLabel"] == "SPEAKER_01"


def _reference_speaker_for_segment(segment: AsrSegment, speaker_turns: list[SpeakerTurn]) -> SpeakerTurn | None:
    """The original O(N) linear scan, kept verbatim as the semantics oracle."""
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


def test_indexed_assignment_matches_linear_scan_on_random_input() -> None:
    # The bisect-bounded index is a pure performance change; assert it picks
    # the IDENTICAL turn object the linear scan would for a messy random mix
    # of overlapping, unsorted, zero-length and gap cases.
    import random

    from ai_worker.pipeline.alignment.transcript_merge import _TurnIndex

    rng = random.Random(20260713)
    for _round in range(50):
        turns = []
        for i in range(rng.randint(1, 40)):
            start = rng.randrange(0, 60_000, 250)
            turns.append(
                SpeakerTurn(
                    speaker_label=f"SPEAKER_{i:02d}",
                    start_ms=start,
                    end_ms=start + rng.randrange(0, 8_000, 250),
                    confidence=0.9,
                )
            )
        rng.shuffle(turns)
        index = _TurnIndex(turns)
        for _segment in range(40):
            start = rng.randrange(-1_000, 70_000, 125)
            segment = AsrSegment(
                start_ms=start,
                end_ms=start + rng.randrange(0, 6_000, 125),
                text="x",
                confidence=0.9,
            )
            assert index.best_for_segment(segment) is _reference_speaker_for_segment(segment, turns)
