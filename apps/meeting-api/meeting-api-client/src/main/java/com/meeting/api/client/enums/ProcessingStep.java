package com.meeting.api.client.enums;

public enum ProcessingStep {
    AUDIO_UPLOAD,
    AUDIO_PREPROCESS,
    ASR,
    ALIGNMENT,
    DIARIZATION,
    SPEAKER_EMBEDDING,
    SPEAKER_MATCHING,
    TRANSCRIPT_MERGE,
    SUMMARY,
    EXTRACTION,
    RAG_INDEXING,
    EXPORT
}
