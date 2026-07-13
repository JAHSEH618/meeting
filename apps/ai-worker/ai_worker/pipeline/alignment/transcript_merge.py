from __future__ import annotations

from bisect import bisect_left
from typing import Any

from ai_worker.domain.task import TaskMessage
from ai_worker.pipeline.asr.runtime import AsrSegment
from ai_worker.pipeline.diarization.runtime import SpeakerTurn


def merge_transcript_segments(
    task: TaskMessage,
    asr_segments: list[AsrSegment],
    speaker_turns: list[SpeakerTurn],
) -> list[dict[str, Any]]:
    turn_index = _TurnIndex(speaker_turns)
    segments: list[dict[str, Any]] = []
    for index, segment in enumerate(asr_segments, start=1):
        turn = turn_index.best_for_segment(segment)
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


class _TurnIndex:
    """Sorted-by-start index for assigning diarization turns to ASR segments.

    Assignment semantics (identical to the previous linear scan): pick the
    turn with the largest temporal overlap with the segment, ties broken by
    the turn appearing EARLIER in the original list. Midpoint-containment
    (the approach before that) mis-assigned overlapping/cross-talk turns and
    silently blamed turn[0] for any segment falling in a gap. When nothing
    overlaps (segment in a gap or spanning a boundary) fall back to the
    nearest turn by time rather than the first one in the list.

    长会议下逐段全量扫描是事件循环上的 O(N×M) 热点,这里按 start_ms
    预排序 + bisect 限定候选窗口:只有 start_ms < seg_end 的 turn 可能
    重叠,而 prefix-max(end_ms) 让反向扫描在更早的 turn 都不可能再重叠
    时提前终止 — 语义与线性扫描逐字节一致,只是不再看每一个 turn。
    """

    def __init__(self, turns: list[SpeakerTurn]) -> None:
        self._turns = turns
        # 原始下标按 start_ms 稳定排序;平局语义依赖原始下标,必须保留。
        self._order = sorted(range(len(turns)), key=lambda i: turns[i].start_ms)
        self._starts = [turns[i].start_ms for i in self._order]
        self._prefix_max_end: list[int] = []
        running_max = 0
        for position, turn_index in enumerate(self._order):
            end_ms = turns[turn_index].end_ms
            running_max = end_ms if position == 0 else max(running_max, end_ms)
            self._prefix_max_end.append(running_max)

    def best_for_segment(self, segment: AsrSegment) -> SpeakerTurn | None:
        turns = self._turns
        if not turns:
            return None
        seg_start = segment.start_ms
        seg_end = max(segment.start_ms, segment.end_ms)

        # 候选窗口:start_ms < seg_end 的 turn 才可能有正重叠。
        upper = bisect_left(self._starts, seg_end)
        best_overlap = 0
        best_original_index = -1
        for position in range(upper - 1, -1, -1):
            if self._prefix_max_end[position] <= seg_start:
                break  # 此位置及更早的 turn 都在 segment 之前结束,不可能重叠
            original_index = self._order[position]
            turn = turns[original_index]
            overlap = min(seg_end, turn.end_ms) - max(seg_start, turn.start_ms)
            if overlap > best_overlap or (
                overlap == best_overlap and overlap > 0 and original_index < best_original_index
            ):
                best_overlap = overlap
                best_original_index = original_index
        if best_original_index >= 0:
            return turns[best_original_index]

        # 无重叠回退:时间上最近的 turn(min 保留原始顺序的平局语义)。
        def _gap(turn: SpeakerTurn) -> int:
            if seg_end <= turn.start_ms:
                return turn.start_ms - seg_end
            if seg_start >= turn.end_ms:
                return seg_start - turn.end_ms
            return 0

        return min(turns, key=_gap)
