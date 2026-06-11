package com.meeting.api.client.internal.callback;

public record SpeakerEnrollmentCallbackCommand(
    CallbackMetadata metadata,
    String tenantId,
    String taskId,
    int attemptNo,
    String speakerProfileId,
    String speakerEnrollmentId,
    String audioFileId,
    PlainEmbedding embedding,
    String artifactManifestId
) {
    public record PlainEmbedding(
        String format,
        int dimension,
        float[] values,
        String checksum,
        String modelVersion,
        Double qualityScore
    ) {
    }
}
