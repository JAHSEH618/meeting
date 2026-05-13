from __future__ import annotations

from typing import Any

from ai_worker.application.workflows.state import InMemoryWorkflowStateStore
from ai_worker.domain.task import PipelineArtifact, TaskMessage


class FakeSmokeWorkflowEngine:
    def __init__(self, state_store: InMemoryWorkflowStateStore) -> None:
        self._state_store = state_store

    async def run_pipeline(self, task: TaskMessage) -> PipelineArtifact:
        self._state_store.start(
            task_id=task.task_id,
            task_type=task.task_type,
            tenant_id=task.tenant_id,
            attempt_no=task.attempt_no,
            trace_id=task.trace_id,
            steps=list(task.pipeline_steps),
        )
        return PipelineArtifact(
            task_id=task.task_id,
            transcript_segments=_smoke_transcript(task),
            artifact_manifest_id=f"artifact_manifest_{task.task_id}_{task.attempt_no}",
            terminal_status="SUCCEEDED",
        )

    async def cancel(self, task_id: str) -> None:
        self._state_store.fail(task_id, "WORKFLOW_CANCELLED", "Workflow cancellation requested")


def _smoke_transcript(task: TaskMessage) -> list[dict[str, Any]]:
    meeting_id = task.meeting_id or "unknown_meeting"
    return [
        {
            "segmentId": f"{task.task_id}_seg_0001",
            "startMs": 0,
            "endMs": 1200,
            "speakerLabel": "SPEAKER_00",
            "text": f"Smoke transcript for {meeting_id}.",
            "asrConfidence": 0.99,
            "diarizationConfidence": 0.98,
            "speakerConfidence": 0.0,
            "timestampPrecision": "SEGMENT",
        }
    ]
