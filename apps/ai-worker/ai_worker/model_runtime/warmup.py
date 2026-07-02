"""Background model warmup shared by the API lifespan and the consumer.

Wires the previously dead ``AI_WORKER_MODEL_WARMUP_ON_STARTUP`` /
``AI_WORKER_MODEL_WARMUP_CAPABILITIES`` settings: without warmup the first
task after a (re)start pays every model's multi-minute cold load inside its
own latency budget — three loads back-to-back for a MEETING_FULL_PIPELINE.

Warmup is strictly best-effort: a runtime that fails to load logs an error
and the per-step ``ensure_loaded()`` remains the authoritative gate, so a
broken weight directory still surfaces as a task failure, never a crash at
boot.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any, Callable, Iterable

from ai_worker.common.config import settings
from ai_worker.model_runtime.registry import (
    get_asr_runtime,
    get_bge_m3,
    get_bge_reranker,
    get_diarization_runtime,
    get_speaker_runtime,
)

logger = logging.getLogger(__name__)

CAPABILITY_GETTERS: dict[str, Callable[[], Any]] = {
    "embedding": get_bge_m3,
    "rerank": get_bge_reranker,
    "asr": get_asr_runtime,
    "diarization": get_diarization_runtime,
    "speaker": get_speaker_runtime,
}


def configured_capabilities() -> list[str]:
    return [
        name.strip()
        for name in settings.model_warmup_capabilities.split(",")
        if name.strip()
    ]


async def warmup_models(capabilities: Iterable[str] | None = None) -> None:
    """Concurrently ensure_loaded() every requested runtime.

    The per-device semaphores inside each runtime still serialize actual
    GPU loads, so firing these concurrently never double-allocates VRAM —
    it just overlaps CPU-side imports and lets a multi-device box load in
    parallel.
    """
    names = list(capabilities) if capabilities is not None else configured_capabilities()
    unknown = [name for name in names if name not in CAPABILITY_GETTERS]
    if unknown:
        logger.warning(
            "model_warmup_unknown_capabilities %s (known: %s)",
            unknown,
            sorted(CAPABILITY_GETTERS),
        )
    known = [name for name in names if name in CAPABILITY_GETTERS]
    if not known:
        return
    logger.info("model_warmup_start capabilities=%s", known)
    await asyncio.gather(
        *(_safe_ensure_loaded(name, CAPABILITY_GETTERS[name]()) for name in known)
    )
    logger.info("model_warmup_done capabilities=%s", known)


async def _safe_ensure_loaded(capability: str, runtime: Any) -> None:
    ensure_loaded = getattr(runtime, "ensure_loaded", None)
    if ensure_loaded is None:
        return
    try:
        await ensure_loaded()
    except Exception:  # noqa: BLE001 — warmup must never take the process down
        logger.exception("model_warmup_failed capability=%s", capability)
