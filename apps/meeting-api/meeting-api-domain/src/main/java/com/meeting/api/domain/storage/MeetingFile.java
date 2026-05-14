package com.meeting.api.domain.storage;

import java.time.OffsetDateTime;

public record MeetingFile(
    String fileId,
    String tenantId,
    String meetingId,
    String fileType,
    String filePurpose,
    String fileName,
    String contentType,
    String bucket,
    String objectKey,
    String uri,
    long sizeBytes,
    String sha256,
    Long durationMs,
    String uploadStatus,
    String createdBy,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
