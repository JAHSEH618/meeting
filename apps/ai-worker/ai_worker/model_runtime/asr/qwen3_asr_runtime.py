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

from ai_worker.common.config import settings
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

        Wrapped in the per-device semaphore so a concurrent cold-start
        with DIAR / embedding doesn't double-allocate VRAM on a single-
        GPU host.
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
                    # Bound the blocking load so a stalled funasr import / weight
                    # load can't hang the step forever. On timeout wait_for
                    # cancels the await (the executor thread may keep running,
                    # but the task fails terminally and Java learns the outcome).
                    await asyncio.wait_for(
                        asyncio.get_running_loop().run_in_executor(
                            None, self._load_model_blocking
                        ),
                        timeout=settings.model_load_timeout_seconds,
                    )
                    self._status = "READY"
                    self._last_error = None
                except asyncio.TimeoutError as exc:
                    self._status = "ERROR"
                    self._last_error = (
                        f"load timed out after {settings.model_load_timeout_seconds}s"
                    )
                    raise Qwen3AsrRuntimeError(
                        "ASR_MODEL_TIMEOUT",
                        f"qwen3-asr load timed out after {settings.model_load_timeout_seconds}s",
                    ) from exc
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

        # FunASR added Qwen3-ASR (0.6B/1.7B) support on 2026-05-20. Loading it
        # requires trust_remote_code=True so funasr can pull in the model's
        # custom code; without it the load fails. models_dir is a locally staged
        # path, so no hub fetch is needed.
        model_kwargs: dict[str, Any] = {
            "model": str(self._models_dir),
            "disable_update": True,
            "trust_remote_code": True,
            "device": self._device,
        }
        if settings.asr_vad_model:
            # With a VAD front-end funasr splits long audio on silence and
            # transcribes segment-by-segment: no mid-word hard cuts, per-
            # segment timestamps, and hours-long meetings stop being a single
            # monolithic generate() call. Offline deployments must stage the
            # VAD weights and point asr_vad_model at the directory.
            model_kwargs["vad_model"] = settings.asr_vad_model
            model_kwargs["vad_kwargs"] = {
                "max_single_segment_time": settings.asr_vad_max_single_segment_ms
            }
        self._model = AutoModel(**model_kwargs)

    # ── inference ──────────────────────────────────────────────────────

    async def transcribe(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        language: str | None,
        context: str | None = None,
    ) -> list[AsrSegment]:
        """Transcribe ``audio_path`` into ordered :class:`AsrSegment` items.

        Fake mode delegates to :class:`DeterministicAsrRuntime` (same
        behaviour as before this runtime existed). Real mode requires
        ``await ensure_loaded()`` to have completed; otherwise raises
        ``ASR_RUNTIME_ERROR``.

        Acquires the per-device async semaphore so a single-GPU host
        serializes ASR/DIAR/embed/rerank calls instead of OOM'ing under
        bursty load. Fake mode's ``device=="fake"`` semaphore is wide
        open, so this stays a no-op for tests.
        """
        from ai_worker.model_runtime.concurrency import get_device_semaphore

        if metadata.duration_ms <= 0:
            raise Qwen3AsrRuntimeError("ASR_EMPTY_RESULT", "audio duration is empty")
        async with get_device_semaphore(self._device):
            if self._use_fake:
                return await self._fake.transcribe(audio_path, metadata, language)
            if self._status != "READY" or self._model is None:
                raise Qwen3AsrRuntimeError(
                    "ASR_RUNTIME_ERROR",
                    "qwen3-asr runtime is not loaded; call await ensure_loaded() first",
                )
            # funasr's `generate` is sync + CPU-bound (or GPU-bound); push
            # to an executor so the FastAPI event loop stays responsive, and
            # bound it with a duration-scaled timeout so a stalled inference
            # surfaces as a terminal ASR_MODEL_TIMEOUT instead of hanging the
            # step (heartbeats would otherwise renew the Java lease forever).
            loop = asyncio.get_running_loop()
            timeout_s = (
                settings.asr_inference_timeout_base_seconds
                + max(1.0, metadata.duration_ms / 60_000.0)
                * settings.asr_inference_timeout_per_audio_minute_seconds
            )
            try:
                return await asyncio.wait_for(
                    loop.run_in_executor(
                        None,
                        self._transcribe_blocking,
                        audio_path,
                        metadata,
                        language,
                        context,
                    ),
                    timeout=timeout_s,
                )
            except asyncio.TimeoutError as exc:
                raise Qwen3AsrRuntimeError(
                    "ASR_MODEL_TIMEOUT",
                    f"qwen3-asr inference exceeded {timeout_s:.0f}s budget "
                    f"for {metadata.duration_ms}ms audio",
                ) from exc

    def _transcribe_blocking(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        language: str | None,
        context: str | None = None,
    ) -> list[AsrSegment]:
        """Sync inference path — runs on the executor pool."""
        generate_kwargs: dict[str, Any] = {
            "input": str(audio_path),
            "language": _normalize_language(language),
            "use_timestamp": True,
            # Ask funasr for per-sentence timing where the model supports it;
            # models that don't simply ignore the extra config key.
            "sentence_timestamp": True,
        }
        if context:
            # Hot-word / context biasing: participant names + glossary terms
            # from the task message. Meeting names and domain terms are the
            # highest-error word class and directly decide minutes ownership.
            generate_kwargs["hotword"] = context
        try:
            result = self._model.generate(**generate_kwargs)
        except Exception as exc:
            raise Qwen3AsrRuntimeError(
                "ASR_RUNTIME_ERROR",
                f"qwen3-asr inference failed: {exc}",
            ) from exc

        segments: list[AsrSegment] = []
        for item in result if isinstance(result, list) else [result]:
            segments.extend(_segments_from_item(item, metadata))
        if not segments:
            raise Qwen3AsrRuntimeError(
                "ASR_EMPTY_RESULT",
                "qwen3-asr produced no segments — possible silent audio",
            )
        return segments


# BCP-47-ish tags Java sends → language codes funasr/Qwen3-ASR expects.
# Unknown values fall back to their primary subtag so "zh-Hans-CN" still
# resolves to "zh" instead of being passed through verbatim (behaviour then
# depends on the model implementation and is hard to debug).
_LANGUAGE_ALIASES = {
    "zh": "zh", "zh-cn": "zh", "zh-tw": "zh", "zh-hk": "zh", "zh-hans": "zh", "zh-hant": "zh",
    "en": "en", "en-us": "en", "en-gb": "en",
    "ja": "ja", "ja-jp": "ja",
    "ko": "ko", "ko-kr": "ko",
    "auto": "auto",
}


def _normalize_language(language: str | None) -> str:
    if not language or not language.strip():
        return settings.asr_default_language
    tag = language.strip().lower()
    if tag in _LANGUAGE_ALIASES:
        return _LANGUAGE_ALIASES[tag]
    return tag.split("-", 1)[0]


# Sentence-ending punctuation used when we have to split a monolithic
# transcript ourselves (no sentence_info from the model).
_SENTENCE_BREAKS = "。！？!?；;\n"


def _segments_from_item(item: dict[str, Any], metadata: AudioMetadata) -> list[AsrSegment]:
    """Build sentence-level AsrSegments from one funasr result item.

    A single-file generate() typically returns ONE item whose text spans the
    whole recording. Collapsing that into a single AsrSegment used to destroy
    speaker attribution downstream: TRANSCRIPT_MERGE assigns each segment to
    exactly one diarization turn, so the entire meeting landed on one speaker.

    Preference order:
      1. ``sentence_info`` (model-provided per-sentence timing) — exact.
      2. Punctuation split with duration pro-rated by character count over the
         item's [start, end] span — approximate but keeps segments small
         enough for turn assignment to work.
      3. The item as a single segment (last resort, e.g. no text breaks).
    """
    text = (item.get("text") or "").strip()
    if not text:
        return []
    confidence = float(item.get("confidence", 0.85))

    sentence_info = item.get("sentence_info") or []
    if isinstance(sentence_info, list) and sentence_info:
        sentences: list[AsrSegment] = []
        for sentence in sentence_info:
            if not isinstance(sentence, dict):
                continue
            sentence_text = (sentence.get("text") or "").strip()
            if not sentence_text:
                continue
            start = sentence.get("start")
            end = sentence.get("end")
            if start is None or end is None:
                continue
            sentences.append(
                AsrSegment(
                    start_ms=int(start),
                    end_ms=max(int(start) + 1, int(end)),
                    text=sentence_text,
                    confidence=confidence,
                )
            )
        if sentences:
            return sentences

    timestamps = item.get("timestamp") or []
    if timestamps:
        item_start = int(timestamps[0][0])
        item_end = int(timestamps[-1][1])
    else:
        item_start = 0
        item_end = max(1, metadata.duration_ms)

    pieces = _split_sentences(text)
    if len(pieces) <= 1:
        return [AsrSegment(start_ms=item_start, end_ms=item_end, text=text, confidence=confidence)]

    total_chars = sum(len(piece) for piece in pieces)
    span = max(1, item_end - item_start)
    segments: list[AsrSegment] = []
    cursor = item_start
    consumed = 0
    for index, piece in enumerate(pieces):
        consumed += len(piece)
        if index == len(pieces) - 1:
            end_ms = item_end
        else:
            end_ms = item_start + int(span * consumed / total_chars)
        end_ms = max(cursor + 1, end_ms)
        segments.append(AsrSegment(start_ms=cursor, end_ms=end_ms, text=piece, confidence=confidence))
        cursor = end_ms
    return segments


def _split_sentences(text: str) -> list[str]:
    pieces: list[str] = []
    current: list[str] = []
    for char in text:
        current.append(char)
        if char in _SENTENCE_BREAKS:
            piece = "".join(current).strip()
            if piece:
                pieces.append(piece)
            current = []
    tail = "".join(current).strip()
    if tail:
        pieces.append(tail)
    return pieces


# Ensure the class satisfies the runtime Protocol contract.
_protocol_check: AsrModelRuntime = Qwen3AsrRuntime(use_fake=True)  # type: ignore[assignment]
del _protocol_check
