package com.meeting.api.client.storage;

import com.meeting.api.client.enums.AudioUploadStatus;
import java.time.OffsetDateTime;

public record AudioUploadPartDTO(
    int partNumber,
    String partSha256,
    String etag,
    long sizeBytes,
    AudioUploadStatus uploadStatus,
    OffsetDateTime uploadedAt
) {
}
