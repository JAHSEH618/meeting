from __future__ import annotations

from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from ai_worker.application.workflows.audio_pipeline import WorkerPipelineError
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.infrastructure.java_callback.client import CallbackResponse
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime
from ai_worker.model_runtime.asr.qwen3_asr_runtime import Qwen3AsrRuntime, Qwen3AsrRuntimeError
from ai_worker.observability.gpu_metrics import is_cuda_oom
from ai_worker.pipeline.audio.preprocess import AudioMetadata

from test_worker_runtime import StubWorkflowEngine, _valid_message


def _fake_oom_class() -> type[Exception]:
    klass = type("OutOfMemoryError", (RuntimeError,), {})
    klass.__module__ = "torch.cuda"
    return klass


def test_is_cuda_oom_matches_torch_oom_without_importing_torch() -> None:
    FakeOom = _fake_oom_class()
    assert is_cuda_oom(FakeOom("CUDA out of memory"))
    assert not is_cuda_oom(RuntimeError("CUDA out of memory"))
    assert not is_cuda_oom(MemoryError("host oom"))


def test_qwen3_transcribe_blocking_maps_cuda_oom_to_asr_gpu_oom() -> None:
    runtime = Qwen3AsrRuntime(use_fake=False, models_dir=None, device="cpu")
    FakeOom = _fake_oom_class()

    class _Model:
        def generate(self, **kwargs):
            raise FakeOom("CUDA out of memory. Tried to allocate 2.00 GiB")

    runtime._model = _Model()
    metadata = AudioMetadata(
        duration_ms=1_000, sample_rate_hz=16_000, channels=1,
        codec="pcm_s16le", bitrate=None, format_name="wav",
    )

    with pytest.raises(Qwen3AsrRuntimeError) as exc_info:
        runtime._transcribe_blocking(Path("x.wav"), metadata, "zh")

    assert exc_info.value.error_code == "ASR_GPU_OOM"


class _OomStepEngine(StubWorkflowEngine):
    async def run_step(self, context, step_name: str) -> None:
        raise WorkerPipelineError("ASR", "ASR_GPU_OOM", "CUDA out of memory", retryable=True)


@pytest.mark.asyncio
async def test_oom_pipeline_failure_sends_fail_then_requests_worker_exit() -> None:
    callback_client = AsyncMock()
    callback_client.update_step.return_value = CallbackResponse(http_status=200, accepted=True)
    callback_client.fail_task.return_value = CallbackResponse(http_status=200, accepted=True)
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=_OomStepEngine(state_store),
        state_store=state_store,
    )
    assert runtime.oom_exit_requested is False

    await runtime.consume_message(_valid_message())

    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["error_code"] == "ASR_GPU_OOM"
    assert fail_kwargs["retryable"] is True
    assert runtime.oom_exit_requested is True
