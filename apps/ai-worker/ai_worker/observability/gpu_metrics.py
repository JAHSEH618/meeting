"""GPU + step-level metrics for the ai-worker (Phase 8.4.2).

Exposes the gauges/counters that Prometheus rules in
``infra/meeting-infra/observability/prometheus/rules.yaml`` consume:

* ``ai_worker_gpu_memory_used_bytes{device}``
* ``ai_worker_gpu_memory_total_bytes{device}``
* ``ai_worker_gpu_utilization_percent{device}``
* ``ai_worker_model_rtf{step}``                 — real-time factor
* ``ai_worker_step_failures_total{step,error_code}``
* ``ai_worker_oom_exits_total``

The module gracefully degrades when ``pynvml`` is not installed
(e.g., CPU-only dev box) — GPU gauges simply stay at zero. Callers
(ASR / diarization actors) wrap their workload with the
``record_step_runtime`` and ``record_step_failure`` helpers; the OOM
handler at the bottom is meant for use in an ``except`` block where
``torch.cuda.OutOfMemoryError`` is raised — it records the metric,
flushes, then exits the worker so Kubernetes can restart it on a
fresh GPU state.
"""

from __future__ import annotations

import logging
import os
import sys
from contextlib import contextmanager
from time import perf_counter
from typing import Iterator

from prometheus_client import Counter, Gauge

logger = logging.getLogger(__name__)

GPU_MEMORY_USED = Gauge(
    "ai_worker_gpu_memory_used_bytes",
    "GPU memory currently allocated, by device index.",
    labelnames=("device",),
)
GPU_MEMORY_TOTAL = Gauge(
    "ai_worker_gpu_memory_total_bytes",
    "GPU memory total capacity, by device index.",
    labelnames=("device",),
)
GPU_UTILIZATION = Gauge(
    "ai_worker_gpu_utilization_percent",
    "GPU SM utilization, by device index.",
    labelnames=("device",),
)

MODEL_RTF = Gauge(
    "ai_worker_model_rtf",
    "Real-time factor per processing step (wall_time / audio_seconds).",
    labelnames=("step",),
)

STEP_FAILURES = Counter(
    "ai_worker_step_failures_total",
    "Total step failures with stable error code.",
    labelnames=("step", "error_code"),
)

OOM_EXITS = Counter(
    "ai_worker_oom_exits_total",
    "Number of times the worker exited because a CUDA OOM was raised.",
)


# pynvml is optional; import lazily so unit tests on macOS/CPU still work.
try:
    import pynvml  # type: ignore[import-not-found]

    _PYNVML_AVAILABLE = True
except ImportError:
    pynvml = None  # type: ignore[assignment]
    _PYNVML_AVAILABLE = False


def _nvml_handles() -> list[tuple[int, object]]:
    if not _PYNVML_AVAILABLE or pynvml is None:
        return []
    try:
        pynvml.nvmlInit()
        return [(i, pynvml.nvmlDeviceGetHandleByIndex(i)) for i in range(pynvml.nvmlDeviceGetCount())]
    except Exception as exc:  # pragma: no cover - env-dependent
        logger.warning("nvml init failed: %s", exc)
        return []


def refresh_gpu_metrics() -> None:
    """Snapshot every GPU device's memory + utilization. Idempotent.

    Designed to be called from a scheduled task (e.g. every 15 s) or from
    the existing ``/metrics`` Prometheus scrape path; both work because
    Prometheus scrapes only see the most recently set gauge value.
    """
    if pynvml is None:
        return
    handles = _nvml_handles()
    for idx, handle in handles:
        try:
            mem = pynvml.nvmlDeviceGetMemoryInfo(handle)
            util = pynvml.nvmlDeviceGetUtilizationRates(handle)
            GPU_MEMORY_USED.labels(device=str(idx)).set(int(mem.used))
            GPU_MEMORY_TOTAL.labels(device=str(idx)).set(int(mem.total))
            GPU_UTILIZATION.labels(device=str(idx)).set(int(util.gpu))
        except Exception as exc:  # pragma: no cover - env-dependent
            logger.warning("nvml read failed for device %s: %s", idx, exc)


@contextmanager
def record_step_runtime(step: str, audio_seconds: float | None = None) -> Iterator[None]:
    """Measure wall-clock time for a processing step and stamp RTF.

    Usage::

        with record_step_runtime("ASR", audio_seconds=segment_duration):
            transcribe_segment(...)

    The gauge can stay zero when ``audio_seconds`` is unknown (e.g.,
    diarization on an aggregate view).
    """
    started = perf_counter()
    try:
        yield
    finally:
        elapsed = perf_counter() - started
        if audio_seconds and audio_seconds > 0:
            MODEL_RTF.labels(step=step).set(elapsed / audio_seconds)


def record_step_failure(step: str, error_code: str) -> None:
    """Increment the step-failure counter with a stable, low-cardinality code."""
    STEP_FAILURES.labels(step=step, error_code=error_code).inc()


def report_oom_and_exit() -> None:
    """Record the OOM exit counter and terminate the process.

    Plan 8.4.2.b — Kubernetes/StatefulSet will restart the pod with a
    fresh CUDA context. The exit code 137 is the SIGKILL convention; we
    use it deliberately so the platform can distinguish OOM exits from
    a clean shutdown.
    """
    try:
        OOM_EXITS.inc()
    finally:
        logger.error("ai_worker_oom_exit pid=%s", os.getpid())
        sys.exit(137)
