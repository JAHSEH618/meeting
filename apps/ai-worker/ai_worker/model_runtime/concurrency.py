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


def get_device_semaphore(device: str) -> asyncio.Semaphore:
    """Return the async semaphore for the given device's family.

    Lazily allocated on first use so we don't bind the semaphore to a
    specific event loop at import time (uvicorn rebinds the loop). The
    default cap is :data:`_DEFAULTS`; unknown families fall back to 1
    so an exotic device label doesn't accidentally bypass the gate.
    """
    family = (device or "cpu").split(":", 1)[0].lower()
    sem = _semaphores.get(family)
    if sem is None:
        sem = asyncio.Semaphore(_DEFAULTS.get(family, 1))
        _semaphores[family] = sem
    return sem


def reset_for_tests() -> None:
    _semaphores.clear()
