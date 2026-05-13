package com.meeting.api.domain.task;

import java.time.OffsetDateTime;

public interface StorageGateway {
    boolean exists(String objectUri);

    StoredObject describe(String objectUri);

    record StoredObject(
        String objectUri,
        String sha256,
        long sizeBytes,
        OffsetDateTime createdAt
    ) {
    }
}
