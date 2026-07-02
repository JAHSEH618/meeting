"""Silence-aligned WAV chunking for long-audio ASR.

Long recordings used to go through funasr as one monolithic ``generate()``
call: no progress signal for the entire (possibly hour-long) inference and an
unbounded single-call budget. These helpers split the pipeline's normalized
16 kHz mono PCM WAV into ~N-minute pieces, cutting only inside detected
silence so no word is ever split at a boundary; the engine transcribes piece
by piece and reports real progress between pieces.

Pure stdlib (``wave`` + RMS over int16 frames) — no ffmpeg dependency, fully
unit-testable with synthesized audio.
"""

from __future__ import annotations

import array
import math
import wave
from dataclasses import dataclass
from pathlib import Path

# 100ms analysis windows; int16 RMS below ~300 is ≈ -41 dBFS — comfortably
# below speech, above typical room-noise floors after normalization.
_FRAME_MS = 100
_SILENCE_RMS = 300.0
_MIN_SILENCE_MS = 300


@dataclass(frozen=True)
class _SilenceRun:
    start_ms: int
    end_ms: int

    @property
    def midpoint_ms(self) -> int:
        return (self.start_ms + self.end_ms) // 2


def find_silence_cut_points_ms(
    wav_path: Path,
    duration_ms: int,
    target_interval_ms: int,
    search_radius_ms: int,
) -> list[int]:
    """Pick one cut point near every multiple of ``target_interval_ms``.

    Prefers the midpoint of the closest detected silence run within
    ``±search_radius_ms`` of each target; falls back to the bare target when
    the neighborhood has no silence (continuous speech), which funasr's own
    VAD then handles like any other stream edge.
    """
    silences = _detect_silence_runs(wav_path)
    cut_points: list[int] = []
    target = target_interval_ms
    while target < duration_ms - target_interval_ms // 2:
        best: int | None = None
        best_distance = search_radius_ms + 1
        for run in silences:
            distance = abs(run.midpoint_ms - target)
            if distance <= search_radius_ms and distance < best_distance:
                best = run.midpoint_ms
                best_distance = distance
        cut = best if best is not None else target
        if not cut_points or cut > cut_points[-1]:
            cut_points.append(cut)
        target += target_interval_ms
    return cut_points


def _detect_silence_runs(wav_path: Path) -> list[_SilenceRun]:
    runs: list[_SilenceRun] = []
    with wave.open(str(wav_path), "rb") as reader:
        if reader.getnchannels() != 1 or reader.getsampwidth() != 2:
            return []
        sample_rate = reader.getframerate()
        frames_per_window = max(1, sample_rate * _FRAME_MS // 1000)
        position_ms = 0
        run_start: int | None = None
        while True:
            raw = reader.readframes(frames_per_window)
            if not raw:
                break
            samples = array.array("h")
            samples.frombytes(raw[: len(raw) - (len(raw) % 2)])
            rms = math.sqrt(sum(s * s for s in samples) / len(samples)) if samples else 0.0
            window_ms = len(samples) * 1000 // sample_rate
            if rms < _SILENCE_RMS:
                if run_start is None:
                    run_start = position_ms
            else:
                if run_start is not None and position_ms - run_start >= _MIN_SILENCE_MS:
                    runs.append(_SilenceRun(run_start, position_ms))
                run_start = None
            position_ms += window_ms
        if run_start is not None and position_ms - run_start >= _MIN_SILENCE_MS:
            runs.append(_SilenceRun(run_start, position_ms))
    return runs


def slice_wav(source: Path, target: Path, start_ms: int, end_ms: int) -> None:
    """Copy ``[start_ms, end_ms)`` of a PCM WAV into a standalone file."""
    with wave.open(str(source), "rb") as reader:
        params = reader.getparams()
        sample_rate = reader.getframerate()
        start_frame = max(0, start_ms * sample_rate // 1000)
        end_frame = max(start_frame, end_ms * sample_rate // 1000)
        reader.setpos(min(start_frame, reader.getnframes()))
        frames = reader.readframes(int(end_frame - start_frame))
    with wave.open(str(target), "wb") as writer:
        writer.setparams(params)
        writer.writeframes(frames)
