package com.meeting.api.client.storage;

import java.time.OffsetDateTime;
import java.util.List;

public record GenericFileUploadSessionDTO(
    String uploadId,
    OffsetDateTime expiresAt,
    int partSizeBytes,
    int maxPartCount,
    String objectKey,
    String bucket,
    String contentType,
    String fileName,
    long fileSizeBytes,
    String fileSha256,
    String fileId,
    List<GenericFileUploadPartDTO> parts
) {
}
