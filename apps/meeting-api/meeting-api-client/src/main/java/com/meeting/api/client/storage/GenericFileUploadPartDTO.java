package com.meeting.api.client.storage;

import java.time.OffsetDateTime;
import java.util.Map;

public record GenericFileUploadPartDTO(
    int partNumber,
    String partSha256,
    long sizeBytes,
    String etag,
    String uploadUrl,
    OffsetDateTime expiresAt,
    Map<String, String> headers
) {
}
