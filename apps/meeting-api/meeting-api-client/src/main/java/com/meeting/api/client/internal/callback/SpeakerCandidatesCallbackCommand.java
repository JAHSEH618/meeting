package com.meeting.api.client.internal.callback;

import java.util.List;

public record SpeakerCandidatesCallbackCommand(
    CallbackMetadata metadata,
    String tenantId,
    String meetingId,
    String taskId,
    int attemptNo,
    List<SpeakerEntry> speakers
) {
    public record SpeakerEntry(
        String speakerLabel,
        List<Candidate> candidates,
        PlainEmbedding embedding
    ) {
    }

    public record Candidate(
        String personId,
        String speakerProfileId,
        double confidence,
        String matchStatus
    ) {
    }

    public record PlainEmbedding(
        String format,
        int dimension,
        float[] values,
        String checksum,
        String modelVersion
    ) {
    }
}
