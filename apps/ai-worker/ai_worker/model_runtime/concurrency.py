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
import logging
from typing import Any, Callable, Dict, TypeVar

_LOG = logging.getLogger(__name__)

_T = TypeVar("_T")

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


async def run_gated_blocking(
    device: str,
    fn: Callable[..., _T],
    *args: Any,
    timeout: float | None,
    lane: str | None = None,
    description: str = "blocking model call",
) -> _T:
    """Run ``fn(*args)`` in the default executor while holding the device slot.

    The crucial difference from ``async with sem: await wait_for(
    run_in_executor(...))``: on timeout (or caller cancellation) the
    executor thread cannot be interrupted — it is still running CUDA/MPS
    kernels and holding device memory. Releasing the semaphore at that
    point would let the next inference start *concurrently with the
    zombie call* and OOM on exactly the degraded path the gate exists to
    protect. Instead the slot stays with the abandoned call and is
    released by a done-callback only when the thread actually exits;
    subsequent inferences queue on the semaphore as usual.
    """
    sem = get_device_semaphore(device, lane)
    await sem.acquire()
    try:
        fut = asyncio.get_running_loop().run_in_executor(None, fn, *args)
    except BaseException:
        sem.release()
        raise
    try:
        result = await asyncio.wait_for(asyncio.shield(fut), timeout=timeout)
    except BaseException:
        if fut.done():
            # fn already finished — wait_for re-raised fn's own exception
            # and the device is free again.
            sem.release()
        else:
            # Timeout or cancellation while the thread is still running:
            # the slot belongs to the zombie call until it exits.
            _LOG.warning(
                "%s abandoned (timeout/cancel); keeping its device slot until the thread exits",
                description,
            )
            fut.add_done_callback(lambda f: _release_abandoned_slot(f, sem, description))
        raise
    sem.release()
    return result


def _release_abandoned_slot(
    fut: "asyncio.Future[Any]", sem: asyncio.Semaphore, description: str
) -> None:
    """Done-callback for abandoned executor calls: log the outcome and
    hand the device slot back. Runs on the event loop, so the plain
    ``sem.release()`` is safe."""
    if fut.cancelled():
        _LOG.warning("%s cancelled before it started; releasing device slot", description)
    else:
        exc = fut.exception()
        if exc is not None:
            _LOG.warning(
                "%s finished with %s after being abandoned; releasing device slot",
                description,
                type(exc).__name__,
            )
        else:
            _LOG.warning("%s finished after being abandoned; releasing device slot", description)
    sem.release()


def reset_for_tests() -> None:
    _semaphores.clear()
