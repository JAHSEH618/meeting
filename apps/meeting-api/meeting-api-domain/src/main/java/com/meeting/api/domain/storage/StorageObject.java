package com.meeting.api.domain.storage;

import java.time.OffsetDateTime;

public record StorageObject(
    String bucket,
    String objectKey,
    long sizeBytes,
    String sha256,
    String etag,
    OffsetDateTime lastModifiedAt
) {
}
