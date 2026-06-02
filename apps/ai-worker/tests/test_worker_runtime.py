from __future__ import annotations

import copy
from unittest.mock import AsyncMock

import pytest

from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.domain.task import PipelineArtifact
from ai_worker.infrastructure.java_callback.client import CallbackResponse
from ai_worker.pipeline.speaker.matcher import SpeakerMatchCandidate, SpeakerMatchResult
from ai_worker.pipeline.speaker.runtime import SpeakerEmbedding
from ai_worker.pipeline.speaker.submit import SpeakerCandidateSubmission
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime


def _valid_message() -> dict:
    return {
        "taskId": "task_runtime_01",
        "taskType": "MEETING_FULL_PIPELINE",
        "tenantId": "tenant_01",
        "meetingId": "mtg_01",
        "audioFileId": "audio_01",
        "audioUri": "oss://meeting-audio-auska/audio_01.wav",
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
        "options": {
            "enableAsr": True,
            "enableAlignment": True,
            "enableDiarization": True,
            "enableSpeakerRecognition": True,
            "enableRagIndexing": True,
        },
        "traceId": "trace_runtime_01",
    }


@pytest.fixture
def callback_client():
    client = AsyncMock()
    client.update_step.return_value = CallbackResponse(http_status=200, accepted=True)
    client.submit_transcript.return_value = CallbackResponse(http_status=200, accepted=True)
    client.submit_speaker_candidates.return_value = CallbackResponse(http_status=200, accepted=True)
    client.complete_worker_phase.return_value = CallbackResponse(http_status=200, accepted=True)
    client.fail_task.return_value = CallbackResponse(http_status=200, accepted=True)
    return client


class StubWorkflowEngine:
    def __init__(self, state_store: InMemoryWorkflowStateStore) -> None:
        self.state_store = state_store
        self.ran_steps: list[str] = []

    def start_pipeline(self, task):
        self.state_store.start(
            task_id=task.task_id,
            task_type=task.task_type,
            tenant_id=task.tenant_id,
            attempt_no=task.attempt_no,
            trace_id=task.trace_id,
            steps=list(task.pipeline_steps),
        )
        return {"task": task}

    async def run_step(self, context, step_name: str) -> None:
        self.ran_steps.append(step_name)

    async def complete_pipeline(self, context) -> PipelineArtifact:
        task = context["task"]
        return PipelineArtifact(
            task_id=task.task_id,
            transcript_segments=[
                {
                    "segmentId": f"{task.task_id}_seg_0001",
                    "startMs": 0,
                    "endMs": 1200,
                    "speakerLabel": "SPEAKER_00",
                    "text": f"Local transcript for {task.meeting_id}.",
                    "asrConfidence": 0.99,
                    "diarizationConfidence": 0.98,
                    "speakerConfidence": 0.0,
                    "timestampPrecision": "SEGMENT",
                }
            ],
            artifact_manifest_id="oss://meeting-artifacts/manifest.json",
            terminal_status="SUCCEEDED",
        )


class StubSpeakerWorkflowEngine(StubWorkflowEngine):
    def start_pipeline(self, task):
        context = super().start_pipeline(task)
        embedding = SpeakerEmbedding(
            speaker_label="SPEAKER_00",
            values=[1.0, 0.0],
            dimension=2,
            model_version="test-speaker",
            checksum="c" * 64,
            quality_score=0.9,
        )
        match = SpeakerMatchResult(
            speaker_label="SPEAKER_00",
            candidates=[
                SpeakerMatchCandidate("alice", "profile_alice_01", 0.99, "CANDIDATE"),
            ],
        )
        context["speaker_submissions"] = [SpeakerCandidateSubmission(embedding, match)]
        return context


@pytest.mark.asyncio
async def test_consume_message_runs_pipeline_steps_and_records_workflow(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = StubWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)

    task = await runtime.consume_message(_valid_message())

    assert task is not None
    snapshot = state_store.get("task_runtime_01")
    assert snapshot is not None
    assert snapshot.status == "SUCCEEDED"
    assert [step.status for step in snapshot.steps] == ["SUCCEEDED"] * len(_valid_message()["pipelineSteps"])
    assert engine.ran_steps == _valid_message()["pipelineSteps"]
    assert callback_client.update_step.await_count == len(_valid_message()["pipelineSteps"]) * 3
    callback_client.submit_transcript.assert_awaited_once()
    callback_client.complete_worker_phase.assert_awaited_once()
    completed_steps = callback_client.complete_worker_phase.await_args.kwargs["completed_steps"]
    assert completed_steps == _valid_message()["pipelineSteps"]


@pytest.mark.asyncio
async def test_consume_message_submits_speaker_candidates(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = StubSpeakerWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)
    captured: dict = {}

    async def capture_speaker_candidates(**kwargs):
        captured["kwargs"] = copy.deepcopy(kwargs)
        return CallbackResponse(http_status=200, accepted=True)

    callback_client.submit_speaker_candidates.side_effect = capture_speaker_candidates

    await runtime.consume_message(_valid_message())

    callback_client.submit_speaker_candidates.assert_awaited_once()
    kwargs = captured["kwargs"]
    assert kwargs["meeting_id"] == "mtg_01"
    assert kwargs["speaker_candidates"][0]["speakerLabel"] == "SPEAKER_00"
    assert kwargs["speaker_candidates"][0]["candidates"][0]["speakerProfileId"] == "profile_alice_01"
    assert kwargs["speaker_candidates"][0]["embedding"]["values"] == [1.0, 0.0]
    assert engine.ran_steps == _valid_message()["pipelineSteps"]


@pytest.mark.asyncio
async def test_speaker_candidate_callback_failure_records_writeback_failed(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = StubSpeakerWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)
    callback_client.submit_speaker_candidates.return_value = CallbackResponse(
        http_status=503,
        accepted=False,
        error_code="WRITEBACK_FAILED",
    )

    await runtime.consume_message(_valid_message())

    snapshot = state_store.get("task_runtime_01")
    assert snapshot is not None
    assert snapshot.status == "FAILED"
    assert snapshot.errorCode == "WRITEBACK_FAILED"
    callback_client.fail_task.assert_awaited_once()
    assert callback_client.fail_task.await_args.kwargs["failed_step"] == "SPEAKER_MATCHING"
    callback_client.complete_worker_phase.assert_not_awaited()


@pytest.mark.asyncio
async def test_step_callback_failure_records_writeback_failed(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=StubWorkflowEngine(state_store),
        state_store=state_store,
    )
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


def test_default_workflow_engine_uses_registry_runtimes() -> None:
    """Phase J ML hardening — pin the wiring fix.

    Previously ``MvpWorkerRuntime`` constructed ``LocalAudioPipelineEngine``
    without arguments, which silently fell back to the deterministic / single-
    speaker fakes regardless of ``AI_WORKER_USE_FAKE_ASR_RUNTIME``. The fix
    injects the registry-resolved runtimes; this test pins that contract by
    introspecting the engine's runtimes after default construction.
    """
    from ai_worker.application.workflows.audio_pipeline import LocalAudioPipelineEngine
    from ai_worker.model_runtime.asr import Qwen3AsrRuntime
    from ai_worker.model_runtime.diarization import PyannoteDiarizationRuntime

    runtime = MvpWorkerRuntime(callback_client=AsyncMock())

    engine = runtime.workflow_engine
    assert isinstance(engine, LocalAudioPipelineEngine)
    # Private attrs by design — the registry-backed singletons stay
    # internal, but a regression here is exactly what we want to catch.
    assert isinstance(engine._asr_runtime, Qwen3AsrRuntime)
    assert isinstance(engine._diarization_runtime, PyannoteDiarizationRuntime)
