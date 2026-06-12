"""E2E (review P1 final gate): the EXACT Java-produced 8-step
MEETING_FULL_PIPELINE message must run to completion through MvpWorkerRuntime
with the production LocalAudioPipelineEngine (fake model runtimes are fine).

Message shape mirrors meeting-api
ProcessingTaskApplicationService.phase2TaskMessagePayload + MEETING_WORKER_STEPS
and validates against
packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json.
"""

from __future__ import annotations

import asyncio
import shutil
import wave
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

from ai_worker.application.workflows.audio_pipeline import LocalAudioPipelineEngine
from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.infrastructure.artifact_store import LocalArtifactStore
from ai_worker.infrastructure.java_callback.client import CallbackResponse
from ai_worker.infrastructure.task_validator import validate_task_message
from ai_worker.infrastructure.worker_runtime import MvpWorkerRuntime
from ai_worker.pipeline.asr.runtime import DeterministicAsrRuntime

# Mirrors ProcessingTaskApplicationService.MEETING_WORKER_STEPS (Java).
JAVA_MEETING_WORKER_STEPS = [
    "AUDIO_PREPROCESS",
    "ASR",
    "ALIGNMENT",
    "DIARIZATION",
    "SPEAKER_EMBEDDING",
    "SPEAKER_MATCHING",
    "TRANSCRIPT_MERGE",
    "RAG_INDEXING",
]

IMPLEMENTED_STEPS = [
    "AUDIO_PREPROCESS",
    "ASR",
    "DIARIZATION",
    "SPEAKER_EMBEDDING",
    "SPEAKER_MATCHING",
    "TRANSCRIPT_MERGE",
]


def _java_task_message(audio_uri: str) -> dict:
    # Field-for-field mirror of phase2TaskMessagePayload (meeting-api):
    # pipelineSteps = MEETING_WORKER_STEPS, options carries enableAlignment=true
    # plus inputAudioSha256/inputAudioSizeBytes, channelMap is mono/1.
    return {
        "taskId": "task_e2e_01",
        "taskType": "MEETING_FULL_PIPELINE",
        "tenantId": "tenant_e2e",
        "meetingId": "mtg_e2e",
        "attemptNo": 1,
        "pipelineSteps": list(JAVA_MEETING_WORKER_STEPS),
        "expectedInputVersion": {
            "chunkStrategyVersion": "default-zh-v1",
            "transcriptVersion": 0,
        },
        "language": "zh",
        "channelMap": {"channelCount": 1, "layout": "mono"},
        "knownParticipants": [],
        "minSpeakers": 1,
        "maxSpeakers": 4,
        "audioFileId": "file_e2e_01",
        "audioUri": audio_uri,
        "options": {
            "enableAsr": True,
            "enableDiarization": True,
            "enableSpeakerRecognition": True,
            "enableRagIndexing": True,
            "enableAlignment": True,
            "inputAudioSha256": "a" * 64,
            "inputAudioSizeBytes": 32044,
        },
        "traceId": "trace_e2e_01",
        "glossaryTerms": ["声纹", "纪要"],
    }


class SlowDeterministicAsrRuntime(DeterministicAsrRuntime):
    """Same deterministic output, but slow enough to observe heartbeats."""

    async def transcribe(self, audio_path, metadata, language):
        await asyncio.sleep(0.12)
        return await super().transcribe(audio_path, metadata, language)


def _write_wav(path: Path, sample_rate: int = 16000, seconds: float = 0.2) -> None:
    frames = int(sample_rate * seconds)
    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(b"\x00\x00" * frames)


@pytest.fixture
def callback_client() -> AsyncMock:
    client = AsyncMock()
    ok = CallbackResponse(http_status=200, accepted=True)
    client.update_step.return_value = ok
    client.submit_artifacts.return_value = ok
    client.submit_transcript.return_value = ok
    client.submit_speaker_candidates.return_value = ok
    client.complete_worker_phase.return_value = ok
    client.fail_task.return_value = ok
    return client


@pytest.mark.asyncio
async def test_java_shaped_full_pipeline_message_completes_with_degraded_steps(
    tmp_path: Path, callback_client: AsyncMock
) -> None:
    if shutil.which("ffprobe") is None:
        pytest.skip("ffprobe is required for the production preprocessor")

    audio_root = tmp_path / "objects"
    audio_path = audio_root / "meeting-audio-auska" / "tenant_e2e" / "mtg_e2e" / "raw.wav"
    audio_path.parent.mkdir(parents=True)
    _write_wav(audio_path)
    audio_path.with_suffix(audio_path.suffix + ".txt").write_text("端到端转录文本", encoding="utf-8")
    audio_uri = "tos://meeting-audio-auska/tenant_e2e/mtg_e2e/raw.wav"

    message = _java_task_message(audio_uri)
    schema_result = validate_task_message(message)
    assert schema_result.valid, schema_result.errors

    state_store = InMemoryWorkflowStateStore()
    engine = LocalAudioPipelineEngine(
        state_store,
        artifact_store=LocalArtifactStore(audio_root),
        asr_runtime=SlowDeterministicAsrRuntime(),
    )
    runtime = MvpWorkerRuntime(
        callback_client=callback_client,
        workflow_engine=engine,
        state_store=state_store,
        heartbeat_interval_seconds=0.02,
    )

    task = await runtime.consume_message(message)

    assert task is not None

    # 1. No /fail of any kind.
    callback_client.fail_task.assert_not_awaited()

    # 2. Worker phase completed with the expected completed/skipped split.
    callback_client.complete_worker_phase.assert_awaited_once()
    complete_kwargs = callback_client.complete_worker_phase.await_args.kwargs
    assert complete_kwargs["status"] == "PARTIAL_SUCCEEDED"
    assert complete_kwargs["completed_steps"] == IMPLEMENTED_STEPS
    assert {s["stepName"] for s in complete_kwargs["skipped_steps"]} == {"ALIGNMENT", "RAG_INDEXING"}

    # 3. Skipped steps never received step callbacks.
    step_callback_names = {c.kwargs["step_name"] for c in callback_client.update_step.await_args_list}
    assert "ALIGNMENT" not in step_callback_names
    assert "RAG_INDEXING" not in step_callback_names

    # 4. Heartbeats were observed during the slow ASR step.
    heartbeats = [
        c for c in callback_client.update_step.await_args_list
        if c.kwargs["status"] == "RUNNING" and c.kwargs["progress"] >= 1
    ]
    assert len(heartbeats) >= 1
    # At least one heartbeat must be from ASR (the intentionally slow step).
    asr_heartbeats = [c for c in heartbeats if c.kwargs["step_name"] == "ASR"]
    assert len(asr_heartbeats) >= 1

    # 5. Transcript + artifacts callbacks carry the real manifest id.
    callback_client.submit_transcript.assert_awaited_once()
    transcript_kwargs = callback_client.submit_transcript.await_args.kwargs
    assert transcript_kwargs["transcript_version"] == 1
    assert transcript_kwargs["artifact_manifest_id"] == "artifact_manifest_task_e2e_01_1"
    assert transcript_kwargs["segments"][0]["text"] == "端到端转录文本"

    callback_client.submit_artifacts.assert_awaited_once()
    artifact_types = {
        a["artifactType"]
        for a in callback_client.submit_artifacts.await_args.kwargs["artifacts"]
    }
    assert {"QUALITY_REPORT", "ASR_RAW", "DIARIZATION_TURNS", "TRANSCRIPT_MERGE", "ARTIFACT_MANIFEST"} <= artifact_types

    # 6. Local workflow state mirrors the terminal status.
    snapshot = state_store.get("task_e2e_01")
    assert snapshot is not None
    assert snapshot.status == "PARTIAL_SUCCEEDED"
