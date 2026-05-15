"""Process-wide singletons for the bge-m3 and bge-reranker-v2-m3 runtimes.

`get_bge_m3()` / `get_bge_reranker()` are cheap to call and always return
the same instance. Tests that need a fresh registry should call
`reset_for_tests()` in a fixture.

Device resolution happens here (not in the runtime) so the
`AI_WORKER_USE_FAKE_RUNTIME=true` path never imports torch.
"""

from __future__ import annotations

from pathlib import Path

from ai_worker.common.config import settings
from ai_worker.model_runtime.embedding import BgeM3Runtime
from ai_worker.model_runtime.rerank import BgeRerankerRuntime


_bge_m3: BgeM3Runtime | None = None
_bge_reranker: BgeRerankerRuntime | None = None


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


def reset_for_tests() -> None:
    """Drop the cached instances. Used by test fixtures that need a fresh
    state (e.g. to flip use_fake mid-test)."""
    global _bge_m3, _bge_reranker
    _bge_m3 = None
    _bge_reranker = None


def _resolve_device() -> str:
    """`auto` → cuda > mps > cpu. Skips the torch probe in fake mode."""
    if settings.model_device != "auto":
        return settings.model_device
    if settings.use_fake_runtime:
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
