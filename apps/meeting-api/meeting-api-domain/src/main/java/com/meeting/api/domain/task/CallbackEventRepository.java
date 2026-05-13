package com.meeting.api.domain.task;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface CallbackEventRepository {
    Optional<CallbackEventRecord> findByIdempotencyKey(String tenantId, String idempotencyKey);

    CallbackEventRecord save(CallbackEventRecord record);

    record CallbackEventRecord(
        String tenantId,
        String taskId,
        String idempotencyKey,
        String bodySha256,
        int attemptNo,
        String responseSha256,
        OffsetDateTime receivedAt
    ) {
    }
}
