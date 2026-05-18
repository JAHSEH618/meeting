"""Diarization model runtimes (pyannote-audio real + single-speaker fake)."""

from ai_worker.model_runtime.diarization.pyannote_runtime import (
    PyannoteDiarizationRuntime,
    PyannoteDiarizationRuntimeError,
)

__all__ = ["PyannoteDiarizationRuntime", "PyannoteDiarizationRuntimeError"]
