package com.meeting.api.domain.task;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface CallbackEventRepository {
    Optional<CallbackEventRecord> findByIdempotencyKey(String tenantId, String idempotencyKey);

    CallbackEventRecord save(CallbackEventRecord record);

    default RecordResult recordOnce(CallbackEventRecord record) {
        Optional<CallbackEventRecord> existing = findByIdempotencyKey(record.tenantId(), record.idempotencyKey());
        if (existing.isPresent()) {
            CallbackEventRecord previous = existing.get();
            if (isSameCallback(previous, record)) {
                return new RecordResult(RecordStatus.REPLAYED, previous);
            }
            return new RecordResult(RecordStatus.BODY_HASH_CONFLICT, previous);
        }
        save(record);
        return new RecordResult(RecordStatus.RECORDED, record);
    }

    private static boolean isSameCallback(CallbackEventRecord previous, CallbackEventRecord current) {
        return previous.bodySha256().equals(current.bodySha256())
            && previous.taskId().equals(current.taskId())
            && previous.workerId().equals(current.workerId())
            && previous.attemptNo() == current.attemptNo()
            && java.util.Objects.equals(previous.leaseOwner(), current.leaseOwner());
    }

    enum RecordStatus {
        RECORDED,
        REPLAYED,
        BODY_HASH_CONFLICT
    }

    record RecordResult(
        RecordStatus status,
        CallbackEventRecord record
    ) {
    }

    record CallbackEventRecord(
        String tenantId,
        String taskId,
        String workerId,
        String idempotencyKey,
        String bodySha256,
        int attemptNo,
        String leaseOwner,
        String responseSha256,
        int httpStatus,
        String errorCode,
        String traceId,
        OffsetDateTime receivedAt
    ) {
    }
}
