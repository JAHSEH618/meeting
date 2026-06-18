"""Process-wide singletons for the bge-m3, bge-reranker, Qwen3-ASR and
pyannote runtimes.

``get_bge_m3()`` / ``get_bge_reranker()`` / ``get_asr_runtime()`` /
``get_diarization_runtime()`` are cheap to call and always return the
same instance. Tests that need a fresh registry should call
``reset_for_tests()`` in a fixture.

Device resolution happens here (not in the runtime) so the
``AI_WORKER_USE_FAKE_RUNTIME=true`` path never imports torch.

Per-model device + dtype (Phase J ML hardening):

* ``AI_WORKER_BGE_M3_DEVICE`` / ``AI_WORKER_BGE_RERANKER_DEVICE`` /
  ``AI_WORKER_ASR_DEVICE`` / ``AI_WORKER_DIARIZATION_DEVICE`` override the
  global ``AI_WORKER_MODEL_DEVICE`` when set. Each defaults to ``auto``
  which resolves to ``cuda`` > ``mps`` > ``cpu`` after a one-time torch
  probe. Specific suffixes like ``cuda:1`` are passed through verbatim.
* dtype follows the device: CUDA → fp16 (FlagEmbedding default); MPS →
  fp32 (PyTorch MPS docs flag fp16 instability on several ops); CPU →
  fp32. Override per model with ``AI_WORKER_BGE_M3_DTYPE`` etc.

References:
  https://docs.pytorch.org/docs/stable/notes/mps
  https://docs.pytorch.org/docs/main/notes/cuda.html
"""

from __future__ import annotations

from pathlib import Path

from ai_worker.common.config import settings
from ai_worker.model_runtime.asr import Qwen3AsrRuntime
from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntime
from ai_worker.model_runtime.embedding import BgeM3Runtime
from ai_worker.model_runtime.rerank import BgeRerankerRuntime
from ai_worker.model_runtime.speaker import CamPlusPlusRuntime


_bge_m3: BgeM3Runtime | None = None
_bge_reranker: BgeRerankerRuntime | None = None
_asr: Qwen3AsrRuntime | None = None
_diarization: PyannoteDiarizationRuntime | None = None
_speaker: CamPlusPlusRuntime | None = None


def get_bge_m3() -> BgeM3Runtime:
    global _bge_m3
    if _bge_m3 is None:
        device = _resolve_device(settings.bge_m3_device, settings.use_fake_runtime)
        _bge_m3 = BgeM3Runtime(
            use_fake=settings.use_fake_runtime,
            models_dir=Path(settings.bge_m3_models_dir)
            if settings.bge_m3_models_dir
            else None,
            device=device,
            batch_size=settings.bge_m3_batch_size,
            use_fp16=_resolve_fp16(device, settings.bge_m3_dtype),
        )
    return _bge_m3


def get_bge_reranker() -> BgeRerankerRuntime:
    global _bge_reranker
    if _bge_reranker is None:
        device = _resolve_device(settings.bge_reranker_device, settings.use_fake_runtime)
        _bge_reranker = BgeRerankerRuntime(
            use_fake=settings.use_fake_runtime,
            models_dir=Path(settings.bge_reranker_models_dir)
            if settings.bge_reranker_models_dir
            else None,
            device=device,
            use_fp16=_resolve_fp16(device, settings.bge_reranker_dtype),
        )
    return _bge_reranker


def get_asr_runtime() -> Qwen3AsrRuntime:
    """Return the process-wide Qwen3-ASR runtime singleton."""
    global _asr
    if _asr is None:
        device = _resolve_device(settings.asr_device, settings.use_fake_asr_runtime)
        _asr = Qwen3AsrRuntime(
            use_fake=settings.use_fake_asr_runtime,
            models_dir=Path(settings.qwen3_asr_models_dir)
            if settings.qwen3_asr_models_dir
            else None,
            device=device,
        )
    return _asr


def get_diarization_runtime() -> PyannoteDiarizationRuntime:
    """Return the process-wide pyannote diarization runtime singleton."""
    global _diarization
    if _diarization is None:
        device = _resolve_device(
            settings.diarization_device, settings.use_fake_diarization_runtime
        )
        _diarization = PyannoteDiarizationRuntime(
            use_fake=settings.use_fake_diarization_runtime,
            models_dir=Path(settings.pyannote_models_dir)
            if settings.pyannote_models_dir
            else None,
            device=device,
        )
    return _diarization


def get_speaker_runtime() -> CamPlusPlusRuntime:
    """Return the process-wide CAM++ speaker embedding runtime singleton."""
    global _speaker
    if _speaker is None:
        device = _resolve_device(
            settings.speaker_device, settings.use_fake_speaker_runtime
        )
        _speaker = CamPlusPlusRuntime(
            use_fake=settings.use_fake_speaker_runtime,
            models_dir=Path(settings.cam_plus_models_dir)
            if settings.cam_plus_models_dir
            else None,
            device=device,
        )
    return _speaker


def reset_for_tests() -> None:
    """Drop the cached instances. Used by test fixtures that need a fresh
    state (e.g. to flip use_fake mid-test)."""
    global _bge_m3, _bge_reranker, _asr, _diarization, _speaker
    _bge_m3 = None
    _bge_reranker = None
    _asr = None
    _diarization = None
    _speaker = None


def _resolve_device(preferred: str, use_fake: bool) -> str:
    """Resolve a per-model device string.

    ``preferred`` is the per-model setting; ``auto`` falls back to the
    global ``AI_WORKER_MODEL_DEVICE`` (also possibly ``auto``), which then
    probes torch for CUDA / MPS.

    Fake-mode runtimes skip the torch probe — we don't want a CPU-only dev
    box to import torch just to satisfy a fake runtime's device label.
    """
    requested = preferred if preferred != "auto" else settings.model_device
    if requested != "auto":
        return requested
    if use_fake:
        return "cpu"
    try:
        import torch  # type: ignore[import-not-found]

        if torch.cuda.is_available():
            return "cuda"
        mps = getattr(getattr(torch, "backends", None), "mps", None)
        if mps is not None and mps.is_available():
            return "mps"
    except (ImportError, OSError, RuntimeError):
        # torch may import but fail on cuda.is_available() if the CUDA
        # driver lib version doesn't match the wheel (OSError on dlopen,
        # RuntimeError on CUDA init). Treat as "no GPU available" and
        # fall through to CPU; /internal/hardware surfaces the real
        # diagnostic so an operator can fix the underlying issue.
        pass
    return "cpu"


def _resolve_fp16(device: str, dtype_setting: str) -> bool:
    """Return whether to ask the embedding/rerank runtime for fp16.

    Default policy: CUDA → fp16, MPS / CPU → fp32. FlagEmbedding flips
    on autocast under ``use_fp16=True``; on MPS several ops still fall
    back to fp32 anyway, but a few (norm / softmax variants) hit numerical
    issues, so we keep MPS on fp32 unless an operator explicitly opts in.

    Explicit values:
      * ``fp16`` → True
      * ``fp32`` → False
      * ``auto`` → CUDA family ⇒ True, else False
    Any other value raises so operators don't silently get fp32 from a
    typo (e.g. ``"fp16 "`` with trailing space or ``"bf16"`` which we
    don't actually support yet).
    """
    value = (dtype_setting or "auto").strip().lower()
    if value == "fp16":
        return True
    if value == "fp32":
        return False
    if value != "auto":
        raise ValueError(
            f"unsupported dtype {dtype_setting!r}; expected auto/fp16/fp32"
        )
    family = device.split(":", 1)[0]
    return family == "cuda"


def resolve_devices_snapshot() -> dict[str, str]:
    """Used by ``/internal/hardware`` to expose the effective device the
    next-loaded singleton would pick. Side-effect free — does not
    instantiate any runtime."""
    return {
        "bgeM3": _resolve_device(settings.bge_m3_device, settings.use_fake_runtime),
        "bgeReranker": _resolve_device(
            settings.bge_reranker_device, settings.use_fake_runtime
        ),
        "asr": _resolve_device(settings.asr_device, settings.use_fake_asr_runtime),
        "diarization": _resolve_device(
            settings.diarization_device, settings.use_fake_diarization_runtime
        ),
        "speaker": _resolve_device(
            settings.speaker_device, settings.use_fake_speaker_runtime
        ),
    }
