from __future__ import annotations

import copy
import asyncio
from unittest.mock import AsyncMock

import pytest

from ai_worker.application.workflows.audio_pipeline import WorkerPipelineError
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.domain.task import PipelineArtifact, TaskMessage
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
        "audioUri": "tos://meeting-audio-auska/audio_01.wav",
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


def _speaker_enrollment_message() -> dict:
    return {
        "taskId": "task_enroll_01",
        "taskType": "SPEAKER_ENROLLMENT",
        "tenantId": "tenant_01",
        "speakerProfileId": "sp_01",
        "speakerEnrollmentId": "se_01",
        "audioFileId": "audio_enroll_01",
        "audioUri": "tos://meeting-audio-auska/enroll.wav",
        "language": "zh",
        "attemptNo": 1,
        "pipelineSteps": ["SPEAKER_EMBEDDING", "SPEAKER_MATCHING"],
        "expectedInputVersion": {"chunkStrategyVersion": "v1"},
        "options": {},
        "traceId": "trace_enroll_01",
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
            artifact_manifest_id="tos://meeting-artifacts/manifest.json",
            terminal_status="SUCCEEDED",
        )


class SlowWorkflowEngine(StubWorkflowEngine):
    async def run_step(self, context, step_name: str) -> None:
        self.ran_steps.append(step_name)
        await asyncio.sleep(0.035)


class ExplodingWorkflowEngine(StubWorkflowEngine):
    async def run_step(self, context, step_name: str) -> None:
        self.ran_steps.append(step_name)
        await asyncio.sleep(0.015)
        raise RuntimeError("pipeline exploded")


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


class StubEnrollmentWorkflowEngine(StubWorkflowEngine):
    def start_pipeline(self, task):
        context = super().start_pipeline(task)
        self.embedding = SpeakerEmbedding(
            speaker_label="SPEAKER_00",
            values=[0.25, -0.5],
            dimension=2,
            model_version="test-speaker",
            checksum="e" * 64,
            quality_score=0.93,
        )
        context["speaker_embeddings"] = [self.embedding]
        return context

    async def run_step(self, context, step_name: str) -> None:
        if step_name == "SPEAKER_MATCHING":
            raise AssertionError("SPEAKER_ENROLLMENT must not run speaker matching")
        await super().run_step(context, step_name)


class NonRetryableFailingWorkflowEngine(StubWorkflowEngine):
    async def run_step(self, context, step_name: str) -> None:
        raise WorkerPipelineError(
            step_name,
            "AUDIO_SOURCE_MISSING",
            "task audioUri is missing",
            retryable=False,
        )


@pytest.mark.asyncio
async def test_execute_step_sends_periodic_heartbeats_while_step_runs(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SlowWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)
    runtime.heartbeat_interval_seconds = 0.01
    task = TaskMessage(
        task_id="task_slow_01",
        task_type="MEETING_FULL_PIPELINE",
        tenant_id="tenant_01",
        meeting_id="mtg_01",
        attempt_no=1,
        pipeline_steps=("ASR",),
        trace_id="trace_slow_01",
    )
    context = engine.start_pipeline(task)

    result = await runtime.execute_step(task, "ASR", context)

    assert result.status == "SUCCEEDED"
    heartbeat_calls = [
        call for call in callback_client.update_step.await_args_list
        if call.kwargs["status"] == "RUNNING" and call.kwargs["progress"] > 0
    ]
    assert len(heartbeat_calls) >= 2


@pytest.mark.asyncio
async def test_execute_step_stops_heartbeat_loop_when_unexpected_error_bubbles(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = ExplodingWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)
    runtime.heartbeat_interval_seconds = 0.01
    task = TaskMessage(
        task_id="task_exploding_01",
        task_type="MEETING_FULL_PIPELINE",
        tenant_id="tenant_01",
        meeting_id="mtg_01",
        attempt_no=1,
        pipeline_steps=("ASR",),
        trace_id="trace_exploding_01",
    )
    context = engine.start_pipeline(task)

    with pytest.raises(RuntimeError, match="pipeline exploded"):
        await runtime.execute_step(task, "ASR", context)

    calls_after_error = callback_client.update_step.await_count
    await asyncio.sleep(0.035)

    assert callback_client.update_step.await_count == calls_after_error


@pytest.mark.asyncio
async def test_stop_closes_callback_client_pool(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=StubWorkflowEngine(state_store),
        state_store=state_store,
    )

    await runtime.stop()

    callback_client.aclose.assert_awaited_once()


@pytest.mark.asyncio
async def test_stop_closes_workflow_resources(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = StubWorkflowEngine(state_store)
    engine.close = AsyncMock()

    class ClosableEmbeddingWorkflow:
        def __init__(self) -> None:
            self.close = AsyncMock()

    embedding_workflow = ClosableEmbeddingWorkflow()

    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        embedding_workflow=embedding_workflow,
        state_store=state_store,
    )

    await runtime.stop()

    engine.close.assert_awaited_once()
    embedding_workflow.close.assert_awaited_once()


@pytest.mark.asyncio
async def test_consume_message_submits_java_transcript_version_and_records_workflow(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = StubWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)
    raw_message = _valid_message()
    raw_message["expectedInputVersion"] = {
        "chunkStrategyVersion": "v1",
        "transcriptVersion": 6,
    }

    task = await runtime.consume_message(raw_message)

    assert task is not None
    snapshot = state_store.get("task_runtime_01")
    assert snapshot is not None
    assert snapshot.status == "SUCCEEDED"
    assert [step.status for step in snapshot.steps] == ["SUCCEEDED"] * len(_valid_message()["pipelineSteps"])
    assert engine.ran_steps == _valid_message()["pipelineSteps"]
    assert callback_client.update_step.await_count == len(_valid_message()["pipelineSteps"]) * 3
    assert callback_client.update_step.await_args_list[0].kwargs["meeting_id"] == "mtg_01"
    callback_client.submit_transcript.assert_awaited_once()
    assert callback_client.submit_transcript.await_args.kwargs["transcript_version"] == 7
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
    assert callback_client.fail_task.await_args.kwargs["meeting_id"] == "mtg_01"
    callback_client.complete_worker_phase.assert_not_awaited()


@pytest.mark.asyncio
async def test_speaker_enrollment_submits_dedicated_embedding_not_candidates(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = StubEnrollmentWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)
    captured: dict = {}

    async def capture_enrollment_embedding(**kwargs):
        captured["kwargs"] = copy.deepcopy(kwargs)
        return CallbackResponse(http_status=200, accepted=True)

    callback_client.submit_speaker_enrollment_embedding.side_effect = capture_enrollment_embedding

    await runtime.consume_message(_speaker_enrollment_message())

    callback_client.submit_speaker_enrollment_embedding.assert_awaited_once()
    callback_client.submit_speaker_candidates.assert_not_awaited()
    assert engine.ran_steps == ["SPEAKER_EMBEDDING"]
    kwargs = captured["kwargs"]
    assert kwargs["speaker_profile_id"] == "sp_01"
    assert kwargs["speaker_enrollment_id"] == "se_01"
    assert kwargs["audio_file_id"] == "audio_enroll_01"
    assert kwargs["embedding"]["values"] == [0.25, -0.5]
    assert "candidates" not in kwargs
    assert engine.embedding.values == [0.0, 0.0]
    complete_kwargs = callback_client.complete_worker_phase.await_args.kwargs
    assert complete_kwargs["speaker_enrollment_id"] == "se_01"
    assert complete_kwargs["completed_steps"] == ["SPEAKER_EMBEDDING"]
    assert complete_kwargs["skipped_steps"] == [
        {"stepName": "SPEAKER_MATCHING", "reason": "NOT_REQUIRED_FOR_ENROLLMENT"}
    ]


@pytest.mark.asyncio
async def test_speaker_enrollment_callback_failure_records_writeback_failed(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = StubEnrollmentWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)
    callback_client.submit_speaker_enrollment_embedding.return_value = CallbackResponse(
        http_status=503,
        accepted=False,
        error_code="WRITEBACK_FAILED",
    )

    await runtime.consume_message(_speaker_enrollment_message())

    snapshot = state_store.get("task_enroll_01")
    assert snapshot is not None
    assert snapshot.status == "FAILED"
    assert snapshot.errorCode == "WRITEBACK_FAILED"
    callback_client.submit_speaker_candidates.assert_not_awaited()
    callback_client.fail_task.assert_awaited_once()
    assert callback_client.fail_task.await_args.kwargs["failed_step"] == "SPEAKER_EMBEDDING"
    assert callback_client.fail_task.await_args.kwargs["speaker_enrollment_id"] == "se_01"
    callback_client.complete_worker_phase.assert_not_awaited()
    assert engine.embedding.values == [0.0, 0.0]


@pytest.mark.asyncio
async def test_non_retryable_pipeline_error_is_reported_to_java(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=NonRetryableFailingWorkflowEngine(state_store),
        state_store=state_store,
    )

    await runtime.consume_message(_valid_message())

    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["failed_step"] == "AUDIO_PREPROCESS"
    assert fail_kwargs["error_code"] == "AUDIO_SOURCE_MISSING"
    assert fail_kwargs["retryable"] is False
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
    assert callback_client.fail_task.await_args.kwargs["meeting_id"] == "mtg_01"


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
    from ai_worker.model_runtime.speaker import CamPlusPlusRuntime

    runtime = MvpWorkerRuntime(callback_client=AsyncMock())

    engine = runtime.workflow_engine
    assert isinstance(engine, LocalAudioPipelineEngine)
    # Private attrs by design — the registry-backed singletons stay
    # internal, but a regression here is exactly what we want to catch.
    assert isinstance(engine._asr_runtime, Qwen3AsrRuntime)
    assert isinstance(engine._diarization_runtime, PyannoteDiarizationRuntime)
    assert isinstance(engine._speaker_embedding_runtime, CamPlusPlusRuntime)
