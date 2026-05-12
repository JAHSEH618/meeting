"""Task consumer — validates incoming messages and dispatches to workflow engine.

This module bridges RabbitMQ message consumption to the workflow engine,
providing fail-fast validation before any step execution begins.
"""

from ai_worker.domain.task import TaskMessage
from ai_worker.infrastructure.task_validator import validate_task_message, validate_pipeline_steps


def validate_and_parse_task_message(raw_message: dict) -> tuple[TaskMessage | None, list[str]]:
    """Validate raw message dict and return a TaskMessage if valid.

    Returns (TaskMessage, []) on success, or (None, errors) on failure.
    This is the entry point called by the RabbitMQ consumer before any step execution.
    """
    schema_result = validate_task_message(raw_message)
    if not schema_result.valid:
        return None, schema_result.errors

    pipeline_steps = tuple(raw_message.get("pipelineSteps", []))
    steps_result = validate_pipeline_steps(raw_message.get("taskType", ""), list(pipeline_steps))
    if not steps_result.valid:
        return None, steps_result.errors

    task_msg = TaskMessage(
        task_id=raw_message["taskId"],
        task_type=raw_message["taskType"],
        tenant_id=raw_message["tenantId"],
        security_level=raw_message.get("securityLevel", "INTERNAL"),
        attempt_no=raw_message.get("attemptNo", 1),
        pipeline_steps=pipeline_steps,
        expected_input_version=raw_message.get("expectedInputVersion", {}),
        trace_id=raw_message.get("traceId", ""),
        meeting_id=raw_message.get("meetingId"),
        document_id=raw_message.get("documentId"),
        speaker_profile_id=raw_message.get("speakerProfileId"),
        speaker_enrollment_id=raw_message.get("speakerEnrollmentId"),
        audio_file_id=raw_message.get("audioFileId"),
        audio_uri=raw_message.get("audioUri"),
        language=raw_message.get("language"),
        channel_map=raw_message.get("channelMap"),
        known_participants=raw_message.get("knownParticipants", []),
        min_speakers=raw_message.get("minSpeakers"),
        max_speakers=raw_message.get("maxSpeakers"),
        options=raw_message.get("options", {}),
        created_at=raw_message.get("createdAt"),
    )
    return task_msg, []