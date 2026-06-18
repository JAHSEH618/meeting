"""bge-m3 text embedding runtime.

Two modes share the same public surface:

- **fake** (default in tests / CI / local coding): a deterministic SHA-256
  derived L2-normalized 1024-dim vector. Same text always produces the same
  vector; different texts produce well-distributed vectors. No model
  weights, no torch, no network.

- **real**: lazy-loads `FlagEmbedding.BGEM3FlagModel` only when
  `ensure_loaded()` is first called. The import sits inside the function
  body so a fake-mode process never touches torch or the FlagEmbedding
  package. Production deployments must `uv sync --extra real-models` and
  point `AI_WORKER_BGE_M3_MODELS_DIR` at the internal artifact mount.

The runtime exposes a minimal status state machine
(`NOT_LOADED → LOADING → READY` or `→ ERROR`) so the
`GET /internal/models` endpoint can report readiness honestly.
"""

from __future__ import annotations

import asyncio
import hashlib
import math
from pathlib import Path
from typing import Any, Literal


ModelStatus = Literal["NOT_LOADED", "LOADING", "READY", "ERROR"]

# bge-m3 dense output dimension. Aligned with the `knowledge_chunks.embedding
# vector(1024)` column in the meeting-api schema; changing this requires a
# Flyway migration and a full reindex.
DENSE_DIMENSION = 1024


class BgeM3RuntimeError(Exception):
    """Raised when the embedding runtime cannot service a request.

    `error_code` maps to stable internal codes consumed by the FastAPI
    layer when shaping `503 EMBEDDING_UNAVAILABLE` responses.
    """

    def __init__(self, error_code: str, message: str) -> None:
        super().__init__(message)
        self.error_code = error_code


def _deterministic_vector(text: str, dim: int) -> list[float]:
    """Stable SHA-256 → unit vector. Chunked rehash gives clean distribution.

    Each 32-d slice is a fresh hash of `(slice_index, text)` so vectors for
    different texts diverge sharply, while vectors for the same text are
    bit-identical across processes.
    """
    floats: list[float] = []
    slices_needed = (dim + 31) // 32
    for slice_idx in range(slices_needed):
        digest = hashlib.sha256(f"{slice_idx}|{text}".encode("utf-8")).digest()
        for byte in digest:
            if len(floats) >= dim:
                break
            floats.append((byte - 128) / 128.0)
    norm = math.sqrt(sum(f * f for f in floats)) or 1.0
    return [f / norm for f in floats]


class BgeM3Runtime:
    """Async-aware bge-m3 embedding runtime with fake/real toggle."""

    DIMENSION = DENSE_DIMENSION
    FAKE_MODEL_VERSION = "bge-m3-fake-v0"
    REAL_MODEL_VERSION = "bge-m3-v1"

    def __init__(
        self,
        *,
        use_fake: bool,
        models_dir: Path | None = None,
        device: str = "cpu",
        batch_size: int = 16,
        use_fp16: bool | None = None,
    ) -> None:
        self._use_fake = use_fake
        self._models_dir = models_dir
        self._device = "fake" if use_fake else device
        self._batch_size = batch_size
        # Default policy: CUDA → fp16, MPS / CPU → fp32. Caller can pass
        # an explicit bool (set by the registry from AI_WORKER_BGE_M3_DTYPE)
        # to override. Fake mode keeps fp16=False to avoid an empty branch
        # in tests that introspect the flag.
        if use_fp16 is None:
            family = device.split(":", 1)[0]
            self._use_fp16 = family == "cuda" and not use_fake
        else:
            self._use_fp16 = use_fp16 and not use_fake
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

    @property
    def batch_size(self) -> int:
        return self._batch_size

    async def ensure_loaded(self) -> None:
        """Idempotently bring the runtime to READY.

        In fake mode this is a no-op (status already READY from __init__).
        In real mode the FlagEmbedding model is loaded inside a thread
        executor so the FastAPI event loop is not blocked for the ~5-15s
        first-load. Concurrent callers serialize behind an asyncio.Lock so
        the heavy load happens exactly once.

        The per-device semaphore wraps the load as well, not just inference:
        on single-GPU hosts a concurrent ASR + embedding cold-start would
        otherwise both allocate VRAM at the same time and OOM. Fake mode's
        ``device=="fake"`` family is permissive so tests stay un-serialised.
        """
        if self._status == "READY":
            return
        from ai_worker.model_runtime.concurrency import get_device_semaphore

        async with get_device_semaphore(self._device):
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
                    raise BgeM3RuntimeError(
                        "EMBEDDING_MODEL_LOAD_FAILED",
                        f"failed to load bge-m3: {exc}",
                    ) from exc

    def _load_model_blocking(self) -> None:
        """Synchronous heavy load. Called from ensure_loaded's executor.

        Imports FlagEmbedding lazily so the fake path never touches torch.
        """
        from FlagEmbedding import BGEM3FlagModel  # type: ignore[import-not-found]

        target = str(self._models_dir) if self._models_dir else "BAAI/bge-m3"
        self._model = BGEM3FlagModel(target, use_fp16=self._use_fp16, device=self._device)

    def embed(self, texts: list[str]) -> list[list[float]]:
        """Return one 1024-dim vector per input text.

        In fake mode: pure deterministic, no model state required.
        In real mode: requires `await ensure_loaded()` to have completed.
        Empty input → empty output (no roundtrip to the model).

        Sync surface — kept for tests and synchronous call paths. Production
        async paths should prefer :meth:`aembed` so the per-device semaphore
        (CUDA/MPS = 1, CPU = 4) gates concurrent inference and a single-GPU
        host doesn't OOM when ASR / DIAR / embed run at once.
        """
        if not texts:
            return []
        if self._use_fake:
            return [_deterministic_vector(t, self.DIMENSION) for t in texts]
        if self._status != "READY" or self._model is None:
            raise BgeM3RuntimeError(
                "EMBEDDING_MODEL_NOT_READY",
                "bge-m3 runtime is not loaded; call await ensure_loaded() first",
            )
        result = self._model.encode(
            texts, batch_size=self._batch_size, return_dense=True
        )
        dense_vecs = result["dense_vecs"]
        # FlagEmbedding returns a numpy array; coerce to list[list[float]].
        return [list(vec) for vec in dense_vecs.tolist()]

    async def aembed(self, texts: list[str]) -> list[list[float]]:
        """Async wrapper that acquires the per-device semaphore and offloads
        the (sync, CPU-bound on CPU; GPU-bound on CUDA) ``embed`` call to a
        thread executor so the FastAPI event loop stays responsive.

        Fake mode also goes through the semaphore — its ``device=="fake"``
        family has effectively-unbounded concurrency, so the gate is a
        no-op except for keeping the gate uniform across call sites.
        """
        if not texts:
            return []
        from ai_worker.model_runtime.concurrency import get_device_semaphore

        async with get_device_semaphore(self._device):
            if self._use_fake:
                return self.embed(texts)
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(None, self.embed, texts)
