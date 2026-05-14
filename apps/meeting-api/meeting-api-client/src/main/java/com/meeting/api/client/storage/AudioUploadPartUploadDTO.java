package com.meeting.api.client.storage;

import java.time.OffsetDateTime;
import java.util.Map;

public record AudioUploadPartUploadDTO(
    String uploadId,
    int partNumber,
    String partSha256,
    String etag,
    String uploadUrl,
    OffsetDateTime expiresAt,
    Map<String, String> headers
) {
}
