from dataclasses import dataclass, field
from datetime import datetime
from typing import Any


@dataclass(frozen=True)
class TaskStepUpdate:
    tenant_id: str
    meeting_id: str
    task_id: str
    step_name: str
    attempt_no: int
    status: str
    progress: int
    heartbeat_at: datetime | None = None


@dataclass(frozen=True)
class TaskMessage:
    task_id: str
    task_type: str
    tenant_id: str
    security_level: str
    attempt_no: int
    pipeline_steps: tuple[str, ...]
    expected_input_version: dict[str, Any] = field(default_factory=dict)
    trace_id: str = ""
    meeting_id: str | None = None
    document_id: str | None = None
    speaker_profile_id: str | None = None
    speaker_enrollment_id: str | None = None
    audio_file_id: str | None = None
    audio_uri: str | None = None
    language: str | None = None
    channel_map: dict[str, Any] | None = None
    known_participants: list[str] = field(default_factory=list)
    min_speakers: int | None = None
    max_speakers: int | None = None
    options: dict[str, Any] = field(default_factory=dict)
    created_at: str | None = None


@dataclass(frozen=True)
class StepResult:
    step_name: str
    status: str
    progress: int = 0
    error_code: str | None = None
    error_message: str | None = None
    artifact_manifest_id: str | None = None


@dataclass(frozen=True)
class PipelineArtifact:
    task_id: str
    transcript_segments: list[dict[str, Any]] = field(default_factory=list)
    speaker_candidates: list[dict[str, Any]] = field(default_factory=list)
    artifact_manifest_id: str | None = None
    terminal_status: str = "SUCCEEDED"