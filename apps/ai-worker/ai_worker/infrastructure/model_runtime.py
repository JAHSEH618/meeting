"""ModelRuntime port — local model loading, inference, and lifecycle.

Implementation: in-process PyTorch / transformers (one-off default).
Alternative: standalone gRPC model server, Ray Serve, vLLM.
"""

from typing import Protocol, runtime_checkable, Any
from enum import Enum


class ModelCapability(str, Enum):
    ASR = "ASR"
    DIARIZATION = "DIARIZATION"
    SPEAKER_EMBEDDING = "SPEAKER_EMBEDDING"
    FORCED_ALIGNMENT = "FORCED_ALIGNMENT"
    TEXT_EMBEDDING = "TEXT_EMBEDDING"
    RERANK = "RERANK"


class ModelStatus(str, Enum):
    UNLOADED = "UNLOADED"
    LOADING = "LOADING"
    LOADED = "LOADED"
    FAILED = "FAILED"


@runtime_checkable
class ModelRuntime(Protocol):
    """Manages local GPU models.

    Responsibilities:
    - Eager-load ASR/Diarization/Speaker models at startup
    - Lazy-load embedding/rerank models on first use
    - GPU memory management: single-GPU serialization, CUDA_VISIBLE_DEVICES binding
    - OOM detection and worker restart signaling
    - Model checksum verification against model_registry
    """

    async def load_model(self, capability: ModelCapability) -> None:
        """Load a model by capability. Idempotent if already loaded."""
        ...

    async def unload_model(self, capability: ModelCapability) -> None:
        """Unload a model to free GPU memory."""
        ...

    async def infer(self, capability: ModelCapability, **inputs: Any) -> Any:
        """Run inference. Model must be LOADED."""
        ...

    def model_status(self, capability: ModelCapability) -> ModelStatus:
        """Return current status of a model."""
        ...

    def loaded_models(self) -> dict[ModelCapability, dict[str, Any]]:
        """Return {capability: {model_name, version, checksum, device, status}}"""
        ...
