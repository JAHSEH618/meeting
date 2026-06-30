"""pyannote-audio 3.x speaker diarization with single-speaker fake fallback.

Mirrors the Qwen3-ASR runtime's structure so both pipelines share the
same lifecycle + observability contract:

- **fake** wraps :class:`SingleSpeakerDiarizationRuntime` (one turn, one
  speaker) — sufficient for CI / local dev where multi-speaker labelling
  is irrelevant to the test.
- **real** lazy-imports ``pyannote.audio.Pipeline.from_pretrained`` from
  a locally staged weights dir. Production deploys must stage the
  ``pyannote/speaker-diarization-3.1`` checkpoint under
  ``AI_WORKER_PYANNOTE_MODELS_DIR`` and run with HF offline mode set.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, Literal

from ai_worker.common.config import settings
from ai_worker.pipeline.audio.preprocess import AudioMetadata
from ai_worker.pipeline.diarization.runtime import (
    DiarizationRuntime,
    DiarizationRuntimeError,
    SingleSpeakerDiarizationRuntime,
    SpeakerTurn,
)


ModelStatus = Literal["NOT_LOADED", "LOADING", "READY", "ERROR"]


class PyannoteDiarizationRuntimeError(DiarizationRuntimeError):
    """Raised when the real pyannote runtime cannot service a request.

    Inherits ``error_code`` so the callback layer maps it through the
    existing ``DIARIZATION_FAILED`` channel.
    """


class PyannoteDiarizationRuntime:
    """Async-aware diarization runtime with fake/real toggle.

    Implements :class:`DiarizationRuntime` Protocol from
    ``ai_worker.pipeline.diarization.runtime``.
    """

    FAKE_MODEL_VERSION = SingleSpeakerDiarizationRuntime.model_version
    REAL_MODEL_VERSION = "pyannote-speaker-diarization-3.1"

    def __init__(
        self,
        *,
        use_fake: bool,
        models_dir: Path | None = None,
        device: str = "cpu",
        min_speakers: int | None = None,
        max_speakers: int | None = None,
    ) -> None:
        self._use_fake = use_fake
        self._models_dir = models_dir
        self._device = "fake" if use_fake else device
        self._pipeline: Any = None
        self._fake = SingleSpeakerDiarizationRuntime()
        self._status: ModelStatus = "READY" if use_fake else "NOT_LOADED"
        self._last_error: str | None = None
        self._load_lock = asyncio.Lock()
        self._min_speakers = min_speakers
        self._max_speakers = max_speakers

    # ── runtime metadata ────────────────────────────────────────

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

    # ── lifecycle ───────────────────────────────────────────────

    async def ensure_loaded(self) -> None:
        if self._status == "READY":
            return
        from ai_worker.model_runtime.concurrency import get_device_semaphore

        # Per-device semaphore wraps the load to keep cold-start VRAM
        # allocation serialised with ASR / embedding on a single-GPU host.
        async with get_device_semaphore(self._device):
            async with self._load_lock:
                if self._status == "READY":
                    return
                self._status = "LOADING"
                try:
                    # Bound the blocking load so a stalled pyannote/torch load
                    # can't hang the step forever (see qwen3_asr_runtime).
                    await asyncio.wait_for(
                        asyncio.get_running_loop().run_in_executor(
                            None, self._load_pipeline_blocking
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
                    raise PyannoteDiarizationRuntimeError(
                        "DIARIZATION_FAILED",
                        f"pyannote load timed out after {settings.model_load_timeout_seconds}s",
                    ) from exc
                except Exception as exc:
                    self._status = "ERROR"
                    self._last_error = f"{type(exc).__name__}: {exc}"
                    raise PyannoteDiarizationRuntimeError(
                        "DIARIZATION_FAILED",
                        f"failed to load pyannote pipeline: {exc}",
                    ) from exc

    def _load_pipeline_blocking(self) -> None:
        if self._models_dir is None or not self._models_dir.exists():
            raise FileNotFoundError(
                f"pyannote weights not found at {self._models_dir} — "
                "stage them under AI_WORKER_PYANNOTE_MODELS_DIR before boot"
            )
        from pyannote.audio import Pipeline  # type: ignore[import-not-found]
        import torch  # type: ignore[import-not-found]

        config_path = self._models_dir / "config.yaml"
        if not config_path.exists():
            raise FileNotFoundError(
                f"pyannote config.yaml not found at {config_path}"
            )
        self._pipeline = Pipeline.from_pretrained(str(config_path))
        if hasattr(self._pipeline, "to"):
            self._pipeline.to(torch.device(self._device))

    # ── inference ───────────────────────────────────────────────

    async def diarize(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        *,
        min_speakers: int | None = None,
        max_speakers: int | None = None,
    ) -> list[SpeakerTurn]:
        """Per-device semaphore wraps the call so DIAR + ASR + embed/rerank
        share a single-GPU host predictably; see qwen3_asr_runtime for the
        same pattern.

        ``min_speakers``/``max_speakers`` come from the task message
        (MEETING_FULL_PIPELINE requires them); per-call values win over the
        instance defaults so the operator-supplied bounds actually reach
        pyannote instead of being silently ignored.
        """
        from ai_worker.model_runtime.concurrency import get_device_semaphore

        if metadata.duration_ms <= 0:
            raise PyannoteDiarizationRuntimeError(
                "DIARIZATION_EMPTY_TURNS", "audio duration is empty"
            )
        effective_min = min_speakers if min_speakers is not None else self._min_speakers
        effective_max = max_speakers if max_speakers is not None else self._max_speakers
        async with get_device_semaphore(self._device):
            if self._use_fake:
                return await self._fake.diarize(
                    audio_path, metadata, min_speakers=min_speakers, max_speakers=max_speakers
                )
            if self._status != "READY" or self._pipeline is None:
                raise PyannoteDiarizationRuntimeError(
                    "DIARIZATION_FAILED",
                    "pyannote runtime is not loaded; call await ensure_loaded() first",
                )
            loop = asyncio.get_running_loop()
            timeout_s = (
                settings.diarization_inference_timeout_base_seconds
                + max(1.0, metadata.duration_ms / 60_000.0)
                * settings.diarization_inference_timeout_per_audio_minute_seconds
            )
            try:
                return await asyncio.wait_for(
                    loop.run_in_executor(
                        None, self._diarize_blocking, audio_path, effective_min, effective_max
                    ),
                    timeout=timeout_s,
                )
            except asyncio.TimeoutError as exc:
                raise PyannoteDiarizationRuntimeError(
                    "DIARIZATION_FAILED",
                    f"pyannote inference exceeded {timeout_s:.0f}s budget "
                    f"for {metadata.duration_ms}ms audio",
                ) from exc

    def _diarize_blocking(
        self,
        audio_path: Path,
        min_speakers: int | None = None,
        max_speakers: int | None = None,
    ) -> list[SpeakerTurn]:
        kwargs: dict[str, Any] = {}
        if min_speakers is not None:
            kwargs["min_speakers"] = min_speakers
        if max_speakers is not None:
            kwargs["max_speakers"] = max_speakers
        try:
            annotation = self._pipeline(str(audio_path), **kwargs)
        except Exception as exc:
            raise PyannoteDiarizationRuntimeError(
                "DIARIZATION_FAILED",
                f"pyannote inference failed: {exc}",
            ) from exc

        turns: list[SpeakerTurn] = []
        for turn, _track, label in annotation.itertracks(yield_label=True):
            turns.append(
                SpeakerTurn(
                    speaker_label=str(label),
                    start_ms=int(turn.start * 1000),
                    end_ms=int(turn.end * 1000),
                    confidence=0.85,
                )
            )
        if not turns:
            raise PyannoteDiarizationRuntimeError(
                "DIARIZATION_EMPTY_TURNS",
                "pyannote produced no speaker turns",
            )
        return turns


# Protocol contract check.
_protocol_check: DiarizationRuntime = PyannoteDiarizationRuntime(use_fake=True)  # type: ignore[assignment]
del _protocol_check
