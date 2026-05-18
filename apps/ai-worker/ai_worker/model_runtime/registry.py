"""Process-wide singletons for the bge-m3, bge-reranker, Qwen3-ASR and
pyannote runtimes.

``get_bge_m3()`` / ``get_bge_reranker()`` / ``get_asr_runtime()`` /
``get_diarization_runtime()`` are cheap to call and always return the
same instance. Tests that need a fresh registry should call
``reset_for_tests()`` in a fixture.

Device resolution happens here (not in the runtime) so the
``AI_WORKER_USE_FAKE_RUNTIME=true`` path never imports torch.
"""

from __future__ import annotations

from pathlib import Path

from ai_worker.common.config import settings
from ai_worker.model_runtime.asr import Qwen3AsrRuntime
from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntime
from ai_worker.model_runtime.embedding import BgeM3Runtime
from ai_worker.model_runtime.rerank import BgeRerankerRuntime


_bge_m3: BgeM3Runtime | None = None
_bge_reranker: BgeRerankerRuntime | None = None
_asr: Qwen3AsrRuntime | None = None
_diarization: PyannoteDiarizationRuntime | None = None


def get_bge_m3() -> BgeM3Runtime:
    global _bge_m3
    if _bge_m3 is None:
        _bge_m3 = BgeM3Runtime(
            use_fake=settings.use_fake_runtime,
            models_dir=Path(settings.bge_m3_models_dir)
            if settings.bge_m3_models_dir
            else None,
            device=_resolve_device(),
        )
    return _bge_m3


def get_bge_reranker() -> BgeRerankerRuntime:
    global _bge_reranker
    if _bge_reranker is None:
        _bge_reranker = BgeRerankerRuntime(
            use_fake=settings.use_fake_runtime,
            models_dir=Path(settings.bge_reranker_models_dir)
            if settings.bge_reranker_models_dir
            else None,
            device=_resolve_device(),
        )
    return _bge_reranker


def get_asr_runtime() -> Qwen3AsrRuntime:
    """Return the process-wide Qwen3-ASR runtime singleton."""
    global _asr
    if _asr is None:
        _asr = Qwen3AsrRuntime(
            use_fake=settings.use_fake_asr_runtime,
            models_dir=Path(settings.qwen3_asr_models_dir)
            if settings.qwen3_asr_models_dir
            else None,
            device=_resolve_device(),
        )
    return _asr


def get_diarization_runtime() -> PyannoteDiarizationRuntime:
    """Return the process-wide pyannote diarization runtime singleton."""
    global _diarization
    if _diarization is None:
        _diarization = PyannoteDiarizationRuntime(
            use_fake=settings.use_fake_diarization_runtime,
            models_dir=Path(settings.pyannote_models_dir)
            if settings.pyannote_models_dir
            else None,
            device=_resolve_device(),
        )
    return _diarization


def reset_for_tests() -> None:
    """Drop the cached instances. Used by test fixtures that need a fresh
    state (e.g. to flip use_fake mid-test)."""
    global _bge_m3, _bge_reranker, _asr, _diarization
    _bge_m3 = None
    _bge_reranker = None
    _asr = None
    _diarization = None


def _resolve_device() -> str:
    """`auto` → cuda > mps > cpu. Skips the torch probe in fake mode."""
    if settings.model_device != "auto":
        return settings.model_device
    if (
        settings.use_fake_runtime
        and settings.use_fake_asr_runtime
        and settings.use_fake_diarization_runtime
    ):
        return "cpu"
    try:
        import torch  # type: ignore[import-not-found]

        if torch.cuda.is_available():
            return "cuda"
        mps = getattr(getattr(torch, "backends", None), "mps", None)
        if mps is not None and mps.is_available():
            return "mps"
    except ImportError:
        pass
    return "cpu"
