from __future__ import annotations

import asyncio
import copy
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


class SkippingWorkflowEngine(StubWorkflowEngine):
    """Engine that declares ALIGNMENT / RAG_INDEXING as degradable skips,
    mirroring LocalAudioPipelineEngine.step_skip_reason."""

    def step_skip_reason(self, task, step_name: str) -> str | None:
        if step_name == "ALIGNMENT":
            return "ALIGNMENT_DISABLED_DEFAULT_OFF"
        if step_name == "RAG_INDEXING":
            return "RAG_INDEXING_REQUIRES_JAVA_CHUNKING"
        return None


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


class CrashingStepEngine(StubWorkflowEngine):
    async def run_step(self, context, step_name: str) -> None:
        if step_name == "DIARIZATION":
            raise ValueError("unexpected I/O explosion")
        await super().run_step(context, step_name)


class CrashingCompleteEngine(StubWorkflowEngine):
    async def complete_pipeline(self, context):
        raise OSError("disk full while writing manifest")


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
    # RUNNING(0) + SUCCEEDED(100) per step; periodic heartbeats don't fire for
    # sub-20s fake steps.
    assert callback_client.update_step.await_count == len(_valid_message()["pipelineSteps"]) * 2
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

    runtime = MvpWorkerRuntime(callback_client=AsyncMock())

    engine = runtime.workflow_engine
    assert isinstance(engine, LocalAudioPipelineEngine)
    # Private attrs by design — the registry-backed singletons stay
    # internal, but a regression here is exactly what we want to catch.
    assert isinstance(engine._asr_runtime, Qwen3AsrRuntime)
    assert isinstance(engine._diarization_runtime, PyannoteDiarizationRuntime)


@pytest.mark.asyncio
async def test_degradable_steps_are_skipped_without_step_callbacks(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SkippingWorkflowEngine(state_store)
    runtime = MvpWorkerRuntime(callback_client=callback_client, workflow_engine=engine, state_store=state_store)

    await runtime.consume_message(_valid_message())

    callback_client.fail_task.assert_not_awaited()
    assert engine.ran_steps == [
        "AUDIO_PREPROCESS", "ASR", "DIARIZATION",
        "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE",
    ]
    step_callback_names = {c.kwargs["step_name"] for c in callback_client.update_step.await_args_list}
    assert "ALIGNMENT" not in step_callback_names
    assert "RAG_INDEXING" not in step_callback_names
    complete_kwargs = callback_client.complete_worker_phase.await_args.kwargs
    assert complete_kwargs["completed_steps"] == [
        "AUDIO_PREPROCESS", "ASR", "DIARIZATION",
        "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE",
    ]
    assert complete_kwargs["skipped_steps"] == [
        {"stepName": "ALIGNMENT", "reason": "ALIGNMENT_DISABLED_DEFAULT_OFF"},
        {"stepName": "RAG_INDEXING", "reason": "RAG_INDEXING_REQUIRES_JAVA_CHUNKING"},
    ]


def _step_test_task() -> TaskMessage:
    return TaskMessage(
        task_id="task_hb_01",
        task_type="MEETING_FULL_PIPELINE",
        tenant_id="tenant_01",
        attempt_no=1,
        pipeline_steps=("ASR",),
        trace_id="trace_hb_01",
        meeting_id="mtg_01",
    )


class SlowStepEngine(StubWorkflowEngine):
    def __init__(self, state_store: InMemoryWorkflowStateStore, delay: float) -> None:
        super().__init__(state_store)
        self.delay = delay

    async def run_step(self, context, step_name: str) -> None:
        await asyncio.sleep(self.delay)
        await super().run_step(context, step_name)


@pytest.mark.asyncio
async def test_execute_step_sends_periodic_heartbeats_while_step_runs(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SlowStepEngine(state_store, delay=0.12)
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        state_store=state_store,
        heartbeat_interval_seconds=0.02,
    )
    task = _step_test_task()
    context = engine.start_pipeline(task)

    result = await runtime.execute_step(task, "ASR", context)

    assert result.status == "SUCCEEDED"
    calls = callback_client.update_step.await_args_list
    assert calls[0].kwargs["status"] == "RUNNING" and calls[0].kwargs["progress"] == 0
    heartbeats = [
        c for c in calls
        if c.kwargs["status"] == "RUNNING" and c.kwargs["progress"] >= 1
    ]
    assert len(heartbeats) >= 2
    running_progress = [c.kwargs["progress"] for c in calls if c.kwargs["status"] == "RUNNING"]
    assert running_progress == sorted(running_progress)  # monotonically non-decreasing
    assert calls[-1].kwargs["status"] == "SUCCEEDED" and calls[-1].kwargs["progress"] == 100


@pytest.mark.asyncio
async def test_heartbeat_failure_never_fails_the_step(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SlowStepEngine(state_store, delay=0.08)
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        state_store=state_store,
        heartbeat_interval_seconds=0.02,
    )

    def respond(**kwargs):
        if kwargs["status"] == "RUNNING" and kwargs["progress"] > 0:
            return CallbackResponse(http_status=503, accepted=False, error_code="WRITEBACK_FAILED")
        return CallbackResponse(http_status=200, accepted=True)

    callback_client.update_step.side_effect = respond
    task = _step_test_task()
    context = engine.start_pipeline(task)

    result = await runtime.execute_step(task, "ASR", context)

    assert result.status == "SUCCEEDED"


@pytest.mark.asyncio
async def test_heartbeat_task_is_cancelled_after_step_completes(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    engine = SlowStepEngine(state_store, delay=0.05)
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        state_store=state_store,
        heartbeat_interval_seconds=0.02,
    )
    task = _step_test_task()
    context = engine.start_pipeline(task)

    await runtime.execute_step(task, "ASR", context)
    calls_after_step = callback_client.update_step.await_count
    await asyncio.sleep(0.08)

    assert callback_client.update_step.await_count == calls_after_step


@pytest.mark.asyncio
async def test_unexpected_step_exception_sends_fail_with_worker_internal_error(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=CrashingStepEngine(state_store),
        state_store=state_store,
    )

    task = await runtime.consume_message(_valid_message())

    assert task is not None  # consume_message must not raise
    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["failed_step"] == "DIARIZATION"
    assert fail_kwargs["error_code"] == "WORKER_INTERNAL_ERROR"
    assert fail_kwargs["retryable"] is True
    callback_client.complete_worker_phase.assert_not_awaited()


@pytest.mark.asyncio
async def test_unexpected_completion_exception_sends_fail_with_worker_internal_error(callback_client) -> None:
    state_store = InMemoryWorkflowStateStore()
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=CrashingCompleteEngine(state_store),
        state_store=state_store,
    )

    task = await runtime.consume_message(_valid_message())

    assert task is not None
    callback_client.fail_task.assert_awaited_once()
    fail_kwargs = callback_client.fail_task.await_args.kwargs
    assert fail_kwargs["error_code"] == "WORKER_INTERNAL_ERROR"
    assert fail_kwargs["retryable"] is True
    # No step was executing — attribute to the last pipeline step.
    assert fail_kwargs["failed_step"] == "RAG_INDEXING"
