"""CAM++ speaker embedding runtime with deterministic fake fallback.

Follows the same pattern as Qwen3-ASR/pyannote/bge runtimes:

- **fake** (default): wraps DeterministicSpeakerEmbeddingRuntime for tests/CI
- **real**: lazy-loads 3D-Speaker CAM++ model for production embeddings

Production requirements:
1. Install: `uv sync --extra real-speaker`
2. Stage weights: `/opt/models/cam_plus/v1`
3. Set offline: `HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1`
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any, Literal

from ai_worker.common.config import settings
from ai_worker.pipeline.audio.preprocess import AudioMetadata
from ai_worker.pipeline.diarization.runtime import SpeakerTurn
from ai_worker.pipeline.speaker.runtime import (
    DeterministicSpeakerEmbeddingRuntime,
    SpeakerEmbedding,
    SpeakerEmbeddingRuntime,
    SpeakerEmbeddingRuntimeError,
)

ModelStatus = Literal["NOT_LOADED", "LOADING", "READY", "ERROR"]


class CamPlusPlusRuntimeError(SpeakerEmbeddingRuntimeError):
    """Raised when CAM++ runtime fails."""


class CamPlusPlusRuntime:
    """Async-aware speaker embedding runtime with fake/real toggle."""

    FAKE_MODEL_VERSION = DeterministicSpeakerEmbeddingRuntime.model_version
    REAL_MODEL_VERSION = "cam++-v1"
    EMBEDDING_DIM = 192

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
        self._fake = DeterministicSpeakerEmbeddingRuntime()
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
    def models_dir(self) -> Path | None:
        return self._models_dir

    async def ensure_loaded(self) -> None:
        """Idempotently load the real model."""
        if self._status == "READY":
            return
        from ai_worker.model_runtime.concurrency import run_gated_blocking

        async with self._load_lock:
            if self._status == "READY":
                return
            self._status = "LOADING"
            try:
                # Bounded like the ASR/pyannote loads: a stalled modelscope
                # import or weight read must become a terminal step failure,
                # not a forever-RUNNING task whose heartbeat keeps renewing
                # the Java lease. run_gated_blocking keeps the device slot
                # with a timed-out load until its thread exits.
                await run_gated_blocking(
                    self._device,
                    self._load_model_blocking,
                    timeout=settings.model_load_timeout_seconds,
                    description="cam++ load",
                )
                self._status = "READY"
                self._last_error = None
            except asyncio.TimeoutError as exc:
                self._status = "ERROR"
                self._last_error = (
                    f"load timed out after {settings.model_load_timeout_seconds}s"
                )
                raise CamPlusPlusRuntimeError(
                    "SPEAKER_EMBEDDING_FAILED",
                    f"cam++ load timed out after {settings.model_load_timeout_seconds}s",
                ) from exc
            except Exception as exc:
                self._status = "ERROR"
                self._last_error = f"{type(exc).__name__}: {exc}"
                raise CamPlusPlusRuntimeError(
                    "SPEAKER_EMBEDDING_FAILED",
                    f"failed to load cam++: {exc}",
                ) from exc

    def _load_model_blocking(self) -> None:
        """Load CAM++ model synchronously."""
        if self._models_dir is None or not self._models_dir.exists():
            raise FileNotFoundError(
                f"CAM++ weights not found at {self._models_dir} — "
                "stage them under AI_WORKER_CAM_PLUS_MODELS_DIR before boot"
            )

        # Lazy import to avoid torch dependency in fake mode.
        try:
            from modelscope.pipelines import pipeline as ms_pipeline  # type: ignore[import-not-found]
        except ImportError as exc:
            raise CamPlusPlusRuntimeError(
                "SPEAKER_EMBEDDING_FAILED",
                "modelscope not installed - run: uv sync --extra real-speaker",
            ) from exc

        self._model = ms_pipeline(
            task="speaker-verification",
            model=str(self._models_dir),
            device=self._device,
        )

    async def embed(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        speaker_turn: SpeakerTurn,
    ) -> SpeakerEmbedding:
        """Extract speaker embedding from audio segment."""
        if self._use_fake:
            return await self._fake.embed(audio_path, metadata, speaker_turn)

        await self.ensure_loaded()
        from ai_worker.model_runtime.concurrency import run_gated_blocking

        # A single-turn embedding is seconds of audio; if inference hangs
        # (NFS stall, driver bug) convert it into a retryable step failure
        # instead of pinning the task RUNNING forever. On timeout the device
        # slot stays with the zombie call until its thread exits.
        try:
            return await run_gated_blocking(
                self._device,
                self._embed_blocking,
                audio_path,
                metadata,
                speaker_turn,
                timeout=settings.speaker_embed_timeout_seconds,
                description="cam++ embedding",
            )
        except asyncio.TimeoutError as exc:
            raise CamPlusPlusRuntimeError(
                "SPEAKER_EMBEDDING_FAILED",
                f"cam++ embedding exceeded {settings.speaker_embed_timeout_seconds:.0f}s "
                f"for turn {speaker_turn.speaker_label} "
                f"[{speaker_turn.start_ms}, {speaker_turn.end_ms}]ms",
            ) from exc

    def _embed_blocking(
        self,
        audio_path: Path,
        metadata: AudioMetadata,
        speaker_turn: SpeakerTurn,
    ) -> SpeakerEmbedding:
        """Synchronous embedding extraction for a single diarization turn.

        Two correctness fixes over the previous implementation:
          * Use the documented ModelScope speaker-verification call —
            ``pipeline([input], output_emb=True)`` returns the embedding under
            ``result['embs']``. The old ``model(audio_in=..., audio_fs=...)``
            call is the *ASR* pipeline convention and does not return ``embs``.
          * Embed the speaker's segment, not the whole meeting: slice
            ``[start_ms, end_ms]`` out of the audio and feed that. Passing a
            file path lets the pipeline resample to the model's 16 kHz itself.
        """
        import hashlib
        import os
        import tempfile

        sample_rate = metadata.sample_rate_hz or 16000
        start_frame = max(0, int(round(speaker_turn.start_ms / 1000 * sample_rate)))
        stop_frame = max(start_frame + 1, int(round(speaker_turn.end_ms / 1000 * sample_rate)))

        tmp_path: str | None = None
        try:
            import soundfile as sf  # type: ignore[import-not-found]  # real-speaker extra

            segment, file_sr = sf.read(
                str(audio_path),
                start=start_frame,
                stop=stop_frame,
                dtype="float32",
                always_2d=True,
            )
            mono = segment[:, 0]
            fd, tmp_path = tempfile.mkstemp(suffix=".wav")
            os.close(fd)
            sf.write(tmp_path, mono, file_sr, format="WAV", subtype="PCM_16")

            result = self._model([tmp_path], output_emb=True)
            embedding_values = list(result["embs"][0].tolist())

            if len(embedding_values) != self.EMBEDDING_DIM:
                raise CamPlusPlusRuntimeError(
                    "SPEAKER_EMBEDDING_FAILED",
                    f"Expected {self.EMBEDDING_DIM} dims, got {len(embedding_values)}",
                )

            checksum = hashlib.sha256(
                ",".join(f"{v:.6f}" for v in embedding_values).encode("utf-8")
            ).hexdigest()

            return SpeakerEmbedding(
                speaker_label=speaker_turn.speaker_label,
                values=embedding_values,
                dimension=self.EMBEDDING_DIM,
                model_version=self.REAL_MODEL_VERSION,
                checksum=checksum,
                quality_score=float(speaker_turn.confidence),
            )
        except CamPlusPlusRuntimeError:
            raise
        except Exception as exc:
            raise CamPlusPlusRuntimeError(
                "SPEAKER_EMBEDDING_FAILED",
                f"CAM++ inference failed: {exc}",
            ) from exc
        finally:
            if tmp_path is not None:
                try:
                    os.remove(tmp_path)
                except OSError:
                    pass


# Protocol contract check.
_protocol_check: SpeakerEmbeddingRuntime = CamPlusPlusRuntime(use_fake=True)  # type: ignore[assignment]
del _protocol_check
