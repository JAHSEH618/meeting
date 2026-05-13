from __future__ import annotations

from unittest.mock import AsyncMock

import pytest

from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.infrastructure.java_callback.client import CallbackResponse
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime


def _valid_message() -> dict:
    return {
        "taskId": "task_runtime_01",
        "taskType": "MEETING_FULL_PIPELINE",
        "tenantId": "tenant_01",
        "meetingId": "mtg_01",
        "audioFileId": "audio_01",
        "audioUri": "tos://meeting-audio/audio_01.wav",
        "securityLevel": "INTERNAL",
        "attemptNo": 1,
        "pipelineSteps": [
            "AUDIO_PREPROCESS",
            "ASR",
            "ALIGNMENT",
            "DIARIZATION",
            "SPEAKER_EMBEDDING",
            "SPEAKER_MATCHING",
            "TRANSCRIPT_MERGE",
            "RAG_INDEXING",
        ],
        "expectedInputVersion": {"chunkStrategyVersion": "v1"},
        "language": "zh",
        "channelMap": {"channelCount": 1, "layout": "mono"},
        "knownParticipants": [],
        "minSpeakers": 1,
        "maxSpeakers": 4,
        "options": {"enableAsr": True},
        "traceId": "trace_runtime_01",
    }


@pytest.fixture
def callback_client():
    client = AsyncMock()
    client.update_step.return_value = CallbackResponse(http_status=200, accepted=True)
    client.submit_transcript.return_value = CallbackResponse(http_status=200, accepted=True)
    client.complete_worker_phase.return_value = CallbackResponse(http_status=200, accepted=True)
    client.fail_task.return_value = CallbackResponse(http_status=200, accepted=True)
    return client


@pytest.mark.asyncio
async def test_consume_message_runs_fake_pipeline_and_records_workflow(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(callback_client=callback_client, state_store=state_store)

    task = await runtime.consume_message(_valid_message())

    assert task is not None
    snapshot = state_store.get("task_runtime_01")
    assert snapshot is not None
    assert snapshot.status == "SUCCEEDED"
    assert [step.status for step in snapshot.steps] == ["SUCCEEDED"] * 8
    assert callback_client.update_step.await_count == 24
    callback_client.submit_transcript.assert_awaited_once()
    callback_client.complete_worker_phase.assert_awaited_once()
    completed_steps = callback_client.complete_worker_phase.await_args.kwargs["completed_steps"]
    assert completed_steps == _valid_message()["pipelineSteps"]


@pytest.mark.asyncio
async def test_step_callback_failure_records_writeback_failed(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(callback_client=callback_client, state_store=state_store)
    callback_client.update_step.side_effect = [
        CallbackResponse(http_status=409, accepted=False, error_code="CALLBACK_IDEMPOTENCY_CONFLICT"),
    ]

    await runtime.consume_message(_valid_message())

    snapshot = state_store.get("task_runtime_01")
    assert snapshot is not None
    assert snapshot.status == "FAILED"
    assert snapshot.errorCode == "WRITEBACK_FAILED"
    callback_client.fail_task.assert_awaited_once()
    assert callback_client.fail_task.await_args.kwargs["error_code"] == "WRITEBACK_FAILED"
