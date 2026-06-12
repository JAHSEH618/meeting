"""Task consumer — validates incoming messages and dispatches to workflow engine.

This module bridges RabbitMQ message consumption to the workflow engine,
providing fail-fast validation before any step execution begins. When validation
fails, it calls back to Java with INVALID_TASK_MESSAGE so the task is marked failed
rather than being silently dropped or retried indefinitely.
"""

import logging

from ai_worker.application.workflows.registry import WORKFLOW_STEPS_BY_TASK_TYPE
from ai_worker.domain.task import TaskMessage
from ai_worker.infrastructure.java_callback.client import JavaCallbackClient
from ai_worker.infrastructure.task_validator import validate_task_message, validate_pipeline_steps

logger = logging.getLogger(__name__)


def _first_step_for_task_type(task_type: str) -> str:
    """Return the first expected pipeline step for a task type.

    Used when failing a task due to invalid message, so the failedStep
    accurately reflects what would have been the first worker step.
    """
    steps = WORKFLOW_STEPS_BY_TASK_TYPE.get(task_type, ())
    if steps:
        return steps[0]
    return "AUDIO_PREPROCESS"


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


async def consume_and_validate(
    raw_message: dict,
    callback_client: JavaCallbackClient,
) -> TaskMessage | None:
    """Consume a raw RabbitMQ message, validate it, and fail-fast on invalid messages.

    If the message fails validation, this function calls back to Java's
    /internal/processing-tasks/{taskId}/fail endpoint with error code
    INVALID_TASK_MESSAGE, ensuring the task is marked failed rather than
    being retried indefinitely.

    Returns the parsed TaskMessage if valid, or None if the message was
    rejected (and the failure callback was attempted).
    """
    task_msg, errors = validate_and_parse_task_message(raw_message)

    if task_msg is not None:
        return task_msg

    task_id = raw_message.get("taskId", "unknown")
    attempt_no = raw_message.get("attemptNo", 1)
    trace_id = raw_message.get("traceId", f"fail-fast-{task_id}")

    logger.error(
        "INVALID_TASK_MESSAGE: task_id=%s errors=%s",
        task_id,
        errors,
    )

    # Map task type to the first expected pipeline step for accurate failure reporting.
    task_type = raw_message.get("taskType", "MEETING_FULL_PIPELINE")
    first_step = _first_step_for_task_type(task_type)

    kwargs = {
        "task_id": task_id,
        "tenant_id": raw_message.get("tenantId", "unknown"),
        "attempt_no": attempt_no,
        "failed_step": first_step,
        "error_code": "INVALID_TASK_MESSAGE",
        "error_message": "; ".join(errors),
        "retryable": False,
        "trace_id": trace_id,
    }
    if raw_message.get("meetingId"):
        kwargs["meeting_id"] = raw_message["meetingId"]
    if raw_message.get("taskType") == "SPEAKER_ENROLLMENT" and raw_message.get("speakerEnrollmentId"):
        kwargs["speaker_enrollment_id"] = raw_message["speakerEnrollmentId"]
    await callback_client.fail_task(**kwargs)

    return None
