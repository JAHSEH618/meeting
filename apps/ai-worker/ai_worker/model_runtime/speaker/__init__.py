"""Speaker embedding runtimes (3D-Speaker CAM++ real + deterministic fake)."""

from ai_worker.model_runtime.speaker.cam_plus_plus_runtime import (
    CamPlusPlusRuntime,
    CamPlusPlusRuntimeError,
)

__all__ = ["CamPlusPlusRuntime", "CamPlusPlusRuntimeError"]
