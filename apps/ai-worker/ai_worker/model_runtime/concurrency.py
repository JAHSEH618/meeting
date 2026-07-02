"""Per-device async semaphores for ML inference.

Why: a Mac MPS backend serializes most heavyweight kernels anyway, but
PyTorch will happily queue overlapping workloads in the executor pool
and balloon resident memory; a single-GPU NVIDIA box similarly OOMs when
ASR and diarization both try to claim VRAM at once. The semaphores below
cap concurrent in-flight calls per device family so the worker degrades
predictably under burst load instead of crashing with CUDA OOM / MPS
allocator errors.

The defaults are conservative:

* ``mps``  → 1   (single in-flight call; MPS allocator doesn't reclaim
                  aggressively across overlapping ops)
* ``cuda`` → 1   (single-GPU NVIDIA — ASR/DIAR share VRAM)
* ``cpu``  → 4   (no GPU memory pressure; bound by the executor pool)

Callers acquire the semaphore for the runtime's resolved device just
before invoking inference, then release on exit. The helper here keys
on the device *family* (string before ``:``) so ``cuda:0`` and ``cuda:1``
share the same lock on single-GPU hosts — operators with multi-GPU
deployments can raise the limit via env in a future iteration.
"""

from __future__ import annotations

import asyncio
from typing import Dict

_DEFAULTS: dict[str, int] = {"mps": 1, "cuda": 1, "cpu": 4, "fake": 32}
_semaphores: Dict[str, asyncio.Semaphore] = {}

# Workload lane for the small interactive models (bge-m3 ~1.1GB fp16 +
# reranker ~1.1GB). With a single shared GPU semaphore, a minutes-long
# ASR/diarization inference held the lock while /internal/embed queued
# behind it and blew Java's request timeout — interactive RAG答疑 was
# effectively down whenever audio was processing. A dedicated single-slot
# lane bounds the extra VRAM to the two small models while keeping the
# heavyweight audio models serialized among themselves.
INTERACTIVE_LANE = "interactive"


def get_device_semaphore(device: str, lane: str | None = None) -> asyncio.Semaphore:
    """Return the async semaphore for the given device's family (+ lane).

    Lazily allocated on first use so we don't bind the semaphore to a
    specific event loop at import time (uvicorn rebinds the loop). The
    default cap is :data:`_DEFAULTS`; unknown families fall back to 1
    so an exotic device label doesn't accidentally bypass the gate.

    ``lane=INTERACTIVE_LANE`` gives embed/rerank their own single slot on
    GPU families (gated by ``gpu_interactive_lane_enabled``); CPU/fake
    families ignore the lane — they were never the bottleneck.
    """
    family = (device or "cpu").split(":", 1)[0].lower()
    key = family
    if lane == INTERACTIVE_LANE and family in ("cuda", "mps"):
        from ai_worker.common.config import settings

        if settings.gpu_interactive_lane_enabled:
            key = f"{family}:{INTERACTIVE_LANE}"
    sem = _semaphores.get(key)
    if sem is None:
        limit = 1 if key.endswith(f":{INTERACTIVE_LANE}") else _DEFAULTS.get(family, 1)
        sem = asyncio.Semaphore(limit)
        _semaphores[key] = sem
    return sem


def reset_for_tests() -> None:
    _semaphores.clear()
