"""Diarization model loader with fallback strategy.

Provides a single entry point for loading diarization models with automatic
fallback from real models to fake implementations when weights are unavailable.
This abstraction allows production code to request the best available runtime
without knowing whether real model weights have been staged.

Usage:
    loader = DiarizationModelLoader()
    runtime = await loader.load()
    turns = await runtime.diarize(audio_path, metadata)

The loader checks for model weights at the configured path. If found, it
returns a real PyannoteDiarizationRuntime. If not, it falls back to the
fake/single-speaker implementation seamlessly.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Literal

from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntime


RuntimeType = Literal["real", "fake"]


class DiarizationModelLoader:
    """Load diarization models with automatic real→fake fallback.

    The loader inspects the configured model path and decides whether to use
    the real pyannote-audio runtime or fall back to the deterministic fake.
    This decision is made at load time, allowing the same application code to
    run with or without GPU hardware and model weights.
    """

    def __init__(
        self,
        model_path: str | Path | None = None,
        device: str = "cpu",
        expected_checksum: str | None = None,
        min_speakers: int | None = None,
        max_speakers: int | None = None,
    ):
        """Initialize the loader.

        Args:
            model_path: Path to pyannote-audio weights. If None, reads from
                AI_WORKER_PYANNOTE_MODELS_DIR environment variable.
                Defaults to /opt/models/pyannote/speaker-diarization-3.1 if unset.
            device: Target device for inference ("cpu" or "cuda").
            expected_checksum: Optional SHA256 checksum for validation
                (format: "sha256:hexdigest"). If provided and verification
                is enabled, loader will refuse to use weights that don't match.
            min_speakers: Optional minimum number of speakers for diarization.
            max_speakers: Optional maximum number of speakers for diarization.
        """
        default_path = "/opt/models/pyannote/speaker-diarization-3.1"
        env_path = os.getenv("AI_WORKER_PYANNOTE_MODELS_DIR", default_path)
        self.model_path = Path(model_path) if model_path else Path(env_path)
        self.device = device
        self.expected_checksum = expected_checksum
        self.min_speakers = min_speakers
        self.max_speakers = max_speakers
        self._runtime: PyannoteDiarizationRuntime | None = None
        self._runtime_type: RuntimeType | None = None

    def is_real_available(self) -> bool:
        """Check if real model weights exist at the configured path.

        Returns:
            True if the model directory exists and appears to contain weights.
            False otherwise.
        """
        return self.model_path.exists() and self.model_path.is_dir()

    async def load(self) -> PyannoteDiarizationRuntime:
        """Load the best available diarization runtime.

        Attempts to load the real pyannote-audio model if weights are available.
        Falls back to the deterministic fake implementation otherwise.

        Returns:
            A PyannoteDiarizationRuntime instance, configured for either real
            or fake mode.

        Note:
            This method is idempotent — calling it multiple times returns the
            same runtime instance. The runtime is created on first call and
            cached for subsequent calls.
        """
        if self._runtime is not None:
            return self._runtime

        if self.is_real_available():
            # Real model weights found — verify checksum if requested
            if self.expected_checksum:
                from ai_worker.observability.model_checksum import compute_checksum

                actual = compute_checksum(str(self.model_path))
                if actual != self.expected_checksum:
                    # Checksum mismatch — treat as unavailable and fall back
                    self._runtime = PyannoteDiarizationRuntime(use_fake=True)
                    self._runtime_type = "fake"
                    return self._runtime

            # Weights present and verified (or verification skipped)
            self._runtime = PyannoteDiarizationRuntime(
                use_fake=False,
                models_dir=self.model_path,
                device=self.device,
                min_speakers=self.min_speakers,
                max_speakers=self.max_speakers,
            )
            self._runtime_type = "real"
            # Trigger lazy load of the real model
            await self._runtime.ensure_loaded()
        else:
            # No weights — use fake runtime
            self._runtime = PyannoteDiarizationRuntime(use_fake=True)
            self._runtime_type = "fake"

        return self._runtime

    @property
    def runtime_type(self) -> RuntimeType | None:
        """Get the type of runtime that was loaded.

        Returns:
            "real" if real weights were loaded, "fake" if using deterministic
            fallback, or None if load() has not been called yet.
        """
        return self._runtime_type

    @property
    def runtime(self) -> PyannoteDiarizationRuntime | None:
        """Get the cached runtime instance.

        Returns:
            The loaded runtime, or None if load() has not been called yet.
        """
        return self._runtime
