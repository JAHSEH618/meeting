"""Workflow step registry used to validate task messages before execution.

The union of WORKFLOW_STEPS_BY_TASK_TYPE must stay within
packages/meeting-contracts/schemas/rabbitmq/processing-task-message.schema.json
pipelineSteps.items.enum; update the contract schema before adding worker steps.
"""

JAVA_OWNED_STEPS = {"AUDIO_UPLOAD", "SUMMARY", "EXTRACTION"}

WORKFLOW_STEPS_BY_TASK_TYPE = {
    "MEETING_FULL_PIPELINE": (
        "AUDIO_PREPROCESS",
        "ASR",
        "ALIGNMENT",
        "DIARIZATION",
        "SPEAKER_EMBEDDING",
        "SPEAKER_MATCHING",
        "TRANSCRIPT_MERGE",
        "RAG_INDEXING",
    ),
    "TEXT_EMBEDDING": ("RAG_INDEXING",),
    "RAG_REINDEX": ("RAG_INDEXING",),
    "SPEAKER_ENROLLMENT": ("SPEAKER_EMBEDDING", "SPEAKER_MATCHING"),
}
