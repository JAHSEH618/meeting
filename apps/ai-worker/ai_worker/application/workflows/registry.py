"""Workflow step registry used to validate task messages before execution."""

JAVA_OWNED_STEPS = {"SUMMARY", "EXTRACTION"}

WORKFLOW_STEPS_BY_TASK_TYPE = {
    "MEETING_FULL_PIPELINE": (
        "AUDIO_UPLOAD",
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
    "EXPORT": ("EXPORT",),
}


def expected_pipeline_steps(task_type: str) -> tuple[str, ...]:
    return WORKFLOW_STEPS_BY_TASK_TYPE[task_type]
