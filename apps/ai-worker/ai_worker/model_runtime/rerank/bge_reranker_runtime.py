"""bge-reranker-v2-m3 reranker runtime.

Same fake/real toggle as `BgeM3Runtime` (see
`ai_worker.model_runtime.embedding.bge_m3_runtime`).

- **fake**: order-preserving scores in `[0.05, 1.0]` so the
  pre-filtered candidates returned from RRF retain their relative order.
  Used in tests, CI and local coding so no model weights are needed.

- **real**: lazy-loads `FlagEmbedding.FlagReranker`. Scores are produced
  by `compute_score(pairs, max_length=512, normalize=True)` and are
  already in `[0, 1]` after sigmoid normalization.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, Literal


ModelStatus = Literal["NOT_LOADED", "LOADING", "READY", "ERROR"]


class BgeRerankerRuntimeError(Exception):
    """Raised when the rerank runtime cannot service a request."""

    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code


class BgeRerankerRuntime:
    """Async-aware bge-reranker-v2-m3 runtime with fake/real toggle."""

    FAKE_MODEL_VERSION = "bge-reranker-v2-m3-fake-v0"
    REAL_MODEL_VERSION = "bge-reranker-v2-m3-v1"
    MAX_LENGTH = 512

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
        self._status: ModelStatus = "READY" if use_fake else "NOT_LOADED"
        self._last_error: str | None = None
        self._load_lock = asyncio.Lock()

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

    async def ensure_loaded(self) -> None:
        """Idempotent load; serializes concurrent calls behind asyncio.Lock."""
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
                raise BgeRerankerRuntimeError(
                    "RERANK_MODEL_LOAD_FAILED",
                    f"failed to load bge-reranker-v2-m3: {exc}",
                ) from exc

    def _load_model_blocking(self) -> None:
        """Synchronous heavy load. Imports FlagEmbedding lazily."""
        from FlagEmbedding import FlagReranker  # type: ignore[import-not-found]

        target = str(self._models_dir) if self._models_dir else "BAAI/bge-reranker-v2-m3"
        use_fp16 = self._device not in ("cpu", "fake")
        self._model = FlagReranker(target, use_fp16=use_fp16, devices=self._device)

    def rank(self, query: str, candidates: list[str]) -> list[float]:
        """Return one score per candidate; higher = more relevant.

        In fake mode: order-preserving scores `1.0 − 0.05·i` clamped at
        0.05. Callers must have pre-sorted candidates by RRF score so the
        rerank result is meaningful.

        In real mode: bge-reranker-v2-m3 with sigmoid normalization, so
        each score sits in `[0, 1]`. The order is query-aware.

        Empty input → empty output. No model call is dispatched.
        """
        if not query:
            raise BgeRerankerRuntimeError(
                "RERANK_INVALID_QUERY", "query must be non-empty"
            )
        if not candidates:
            return []
        if self._use_fake:
            return [max(0.05, round(1.0 - i * 0.05, 4)) for i in range(len(candidates))]
        if self._status != "READY" or self._model is None:
            raise BgeRerankerRuntimeError(
                "RERANK_MODEL_NOT_READY",
                "bge-reranker-v2-m3 runtime is not loaded; call await ensure_loaded() first",
            )
        pairs = [[query, c] for c in candidates]
        scores = self._model.compute_score(
            pairs, max_length=self.MAX_LENGTH, normalize=True
        )
        # FlagReranker returns float for single pair, list[float] otherwise.
        if isinstance(scores, float):
            return [float(scores)]
        return [float(s) for s in scores]
