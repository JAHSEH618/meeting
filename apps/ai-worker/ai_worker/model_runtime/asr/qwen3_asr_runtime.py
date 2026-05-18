"""Qwen3-ASR runtime — real Tongyi Qianwen ASR with deterministic fake fallback.

Follows the same shape as the bge-m3 / bge-reranker runtimes:

- **fake** (default in tests / CI / local coding): wraps the existing
  ``DeterministicAsrRuntime`` so callers get the same Protocol surface
  without any model dependency.
- **real**: lazy-loads ``funasr.AutoModel`` only when ``ensure_loaded()``
  is first called. The import sits inside the function body so a
  fake-mode process never touches torch or funasr. Production deployments
  must:
    1. ``uv sync --extra real-models`` to install funasr + torch.
    2. Stage Qwen3-ASR weights under ``AI_WORKER_QWEN3_ASR_MODELS_DIR``.
    3. Set ``HF_HUB_OFFLINE=1`` + ``TRANSFORMERS_OFFLINE=1`` so the
       runtime never silently downloads from HuggingFace mid-prod.

The runtime exposes the same minimal state machine
(``NOT_LOADED → LOADING → READY`` or ``→ ERROR``) so ``GET /internal/models``
can report readiness honestly, and ``ASR_GPU_OOM`` is handled by the
existing OOM-exit guard in ``ai_worker.observability.gpu_metrics``.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, Literal

from ai_worker.pipeline.asr.runtime import (
    AsrModelRuntime,
    AsrRuntimeError,
    AsrSegment,
    DeterministicAsrRuntime,
)
from ai_worker.pipeline.audio.preprocess import AudioMetadata


ModelStatus = Literal["NOT_LOADED", "LOADING", "READY", "ERROR"]


class Qwen3AsrRuntimeError(AsrRuntimeError):
    """Raised when the real Qwen3-ASR runtime cannot service a request.

    Inherits ``error_code`` from :class:`AsrRuntimeError` so the
    callback layer maps it to ``ASR_MODEL_TIMEOUT`` / ``ASR_RUNTIME_ERROR``
    consistently with the deterministic fallback.
    """


class Qwen3AsrRuntime:
    """Async-aware ASR runtime with fake/real toggle.

    Implements :class:`AsrModelRuntime` Protocol from
    ``ai_worker.pipeline.asr.runtime`` so it slots into the worker
    pipeline as a drop-in replacement for ``DeterministicAsrRuntime``.
    """

    FAKE_MODEL_VERSION = DeterministicAsrRuntime.model_version
    REAL_MODEL_VERSION = "qwen3-asr-v1"
    # Default Qwen3-ASR sample-rate expectation (matches Tongyi's docs).
    EXPECTED_SAMPLE_RATE_HZ = 16_000

    def __init__(
        self,
        *,
        use_fake: bool,
        models_dir: Path | None = None,
        device: str = "cpu",
    ) -> None:
        self._use_fake = use_fake
        self._models_dir = models_dir
        self._device = "fake" if use_fake else device
        self._model: Any = None
        self._fake = DeterministicAsrRuntime()
        self._status: ModelStatus = "READY" if use_fake else "NOT_LOADED"
        self._last_error: str | None = None
        self._load_lock = asyncio.Lock()

    # ── runtime metadata (exposed via /internal/models) ────────────────

    @property
    def model_version(self) -> str:
        return self.FAKE_MODEL_VERSION if self._use_fake else self.REAL_MODEL_VERSION

    @property
    def status(self) -> ModelStatus:
        return self._status

    @property
    def last_error(self) -> str | None:
        return self._last_error

    @property
    def device(self) -> str:
        return self._device

    @property
    def use_fake(self) -> bool:
        return self._use_fake

    @property
    def models_dir(self) -> Path | None:
        return self._models_dir

    # ── lifecycle ──────────────────────────────────────────────────────

    async def ensure_loaded(self) -> None:
        """Idempotently bring the runtime to READY.

        In fake mode this is a no-op. In real mode the funasr model is
        loaded inside a thread executor so the FastAPI event loop is
        not blocked for the multi-second first-load. Concurrent callers
        serialize behind an asyncio.Lock so the heavy load happens
        exactly once.
        """
        if self._status == "READY":
            return
        async with self._load_lock:
            if self._status == "READY":
                return
            self._status = "LOADING"
            try:
                await asyncio.get_running_loop().run_in_executor(
                    None, self._load_model_blocking
                )
                self._status = "READY"
                self._last_error = None
            except Exception as exc:
                self._status = "ERROR"
                self._last_error = f"{type(exc).__name__}: {exc}"
                raise Qwen3AsrRuntimeError(
                    "ASR_MODEL_TIMEOUT",
                    f"failed to load qwen3-asr: {exc}",
                ) from exc

    def _load_model_blocking(self) -> None:
        """Synchronous heavy load. Called from ensure_loaded's executor.

        Imports funasr lazily so the fake path never touches torch.
        Production must point ``models_dir`` at a fully staged weight
        directory; HuggingFace Hub downloads at runtime are blocked
        by ``HF_HUB_OFFLINE=1`` in the prod Dockerfile.
        """
        if self._models_dir is None or not self._models_dir.exists():
            raise FileNotFoundError(
                f"qwen3-asr weights not found at {self._models_dir} — "
                "stage them under AI_WORKER_QWEN3_ASR_MODELS_DIR before boot"
            )
        from funasr import AutoModel  # type: ignore[import-not-found]

        self._model = AutoModel(
            model=str(self._models_dir),
            disable_update=True,
            device=self._device,
        )

    # ── inference ──────────────────────────────────────────────────────

    async def transcribe(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        language: str | None,
    ) -> list[AsrSegment]:
        """Transcribe ``audio_path`` into ordered :class:`AsrSegment` items.

        Fake mode delegates to :class:`DeterministicAsrRuntime` (same
        behaviour as before this runtime existed). Real mode requires
        ``await ensure_loaded()`` to have completed; otherwise raises
        ``ASR_RUNTIME_ERROR``.
        """
        if metadata.duration_ms <= 0:
            raise Qwen3AsrRuntimeError("ASR_EMPTY_RESULT", "audio duration is empty")
        if self._use_fake:
            return await self._fake.transcribe(audio_path, metadata, language)
        if self._status != "READY" or self._model is None:
            raise Qwen3AsrRuntimeError(
                "ASR_RUNTIME_ERROR",
                "qwen3-asr runtime is not loaded; call await ensure_loaded() first",
            )
        # funasr's `generate` is sync + CPU-bound (or GPU-bound); push
        # to an executor so the FastAPI event loop stays responsive.
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            None,
            self._transcribe_blocking,
            audio_path,
            metadata,
            language,
        )

    def _transcribe_blocking(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        language: str | None,
    ) -> list[AsrSegment]:
        """Sync inference path — runs on the executor pool."""
        try:
            result = self._model.generate(
                input=str(audio_path),
                language=language or "zh",
                use_timestamp=True,
            )
        except Exception as exc:
            raise Qwen3AsrRuntimeError(
                "ASR_RUNTIME_ERROR",
                f"qwen3-asr inference failed: {exc}",
            ) from exc

        segments: list[AsrSegment] = []
        for item in result if isinstance(result, list) else [result]:
            text = (item.get("text") or "").strip()
            if not text:
                continue
            timestamps = item.get("timestamp") or []
            if timestamps:
                start_ms = int(timestamps[0][0])
                end_ms = int(timestamps[-1][1])
            else:
                start_ms = 0
                end_ms = max(1, metadata.duration_ms)
            segments.append(
                AsrSegment(
                    start_ms=start_ms,
                    end_ms=end_ms,
                    text=text,
                    confidence=float(item.get("confidence", 0.85)),
                )
            )
        if not segments:
            raise Qwen3AsrRuntimeError(
                "ASR_EMPTY_RESULT",
                "qwen3-asr produced no segments — possible silent audio",
            )
        return segments


# Ensure the class satisfies the runtime Protocol contract.
_protocol_check: AsrModelRuntime = Qwen3AsrRuntime(use_fake=True)  # type: ignore[assignment]
del _protocol_check
