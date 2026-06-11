from ai_worker.infrastructure.task_validator import validate_task_message, validate_pipeline_steps


def test_validate_valid_meeting_full_pipeline() -> None:
    message = {
        "taskId": "task_01",
        "taskType": "MEETING_FULL_PIPELINE",
        "tenantId": "tenant_01",
        "meetingId": "mtg_01",
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
        "channelMap": {"channelCount": 2, "layout": "stereo"},
        "knownParticipants": ["p1"],
        "minSpeakers": 2,
        "maxSpeakers": 5,
        "audioFileId": "file_01",
        "audioUri": "oss://meeting-audio-auska/file_01.wav",
        "options": {},
        "traceId": "trace_01",
    }
    result = validate_task_message(message)
    assert result.valid, f"Expected valid, got errors: {result.errors}"


def test_validate_invalid_missing_required() -> None:
    message = {"taskId": "task_01"}
    result = validate_task_message(message)
    assert not result.valid


def test_validate_pipeline_steps_rejects_java_owned() -> None:
    result = validate_pipeline_steps("MEETING_FULL_PIPELINE", ["AUDIO_UPLOAD", "ASR"])
    assert not result.valid
    assert any("Java-owned" in e for e in result.errors)


def test_validate_pipeline_steps_rejects_export() -> None:
    result = validate_pipeline_steps("MEETING_FULL_PIPELINE", ["AUDIO_PREPROCESS", "EXPORT"])
    assert not result.valid
    assert any("EXPORT" in e for e in result.errors)


def test_validate_pipeline_steps_accepts_valid() -> None:
    result = validate_pipeline_steps(
        "MEETING_FULL_PIPELINE",
        ["AUDIO_PREPROCESS", "ASR", "ALIGNMENT", "DIARIZATION", "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE", "RAG_INDEXING"],
    )
    assert result.valid, f"Expected valid, got errors: {result.errors}"


def test_validate_pipeline_steps_rejects_missing_required_worker_steps() -> None:
    result = validate_pipeline_steps(
        "MEETING_FULL_PIPELINE",
        ["AUDIO_PREPROCESS", "ASR", "DIARIZATION", "TRANSCRIPT_MERGE"],
    )
    assert not result.valid
    assert any("exact ordered steps" in e for e in result.errors)


def test_validate_pipeline_steps_rejects_reordered_required_worker_steps() -> None:
    result = validate_pipeline_steps(
        "MEETING_FULL_PIPELINE",
        ["ASR", "AUDIO_PREPROCESS", "ALIGNMENT", "DIARIZATION", "SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "TRANSCRIPT_MERGE", "RAG_INDEXING"],
    )
    assert not result.valid
    assert any("exact ordered steps" in e for e in result.errors)


def test_validate_pipeline_steps_accepts_exact_speaker_enrollment_steps() -> None:
    result = validate_pipeline_steps("SPEAKER_ENROLLMENT", ["SPEAKER_EMBEDDING", "SPEAKER_MATCHING"])
    assert result.valid, f"Expected valid, got errors: {result.errors}"


def test_validate_pipeline_steps_rejects_incomplete_speaker_enrollment_steps() -> None:
    result = validate_pipeline_steps("SPEAKER_ENROLLMENT", ["SPEAKER_EMBEDDING"])
    assert not result.valid
    assert any("exact ordered steps" in e for e in result.errors)


def test_validate_pipeline_steps_rejects_extra_speaker_enrollment_steps() -> None:
    result = validate_pipeline_steps("SPEAKER_ENROLLMENT", ["SPEAKER_EMBEDDING", "SPEAKER_MATCHING", "RAG_INDEXING"])
    assert not result.valid
    assert any("unexpected steps" in e for e in result.errors)
    assert any("exact ordered steps" in e for e in result.errors)
