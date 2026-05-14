package com.meeting.api.client.storage;

import com.meeting.api.client.enums.AudioUploadStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record AudioUploadSessionDTO(
    String uploadId,
    String meetingId,
    AudioUploadStatus uploadStatus,
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
    List<AudioUploadPartDTO> parts
) {
}
