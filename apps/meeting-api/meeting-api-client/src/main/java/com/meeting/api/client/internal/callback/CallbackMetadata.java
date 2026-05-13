package com.meeting.api.client.internal.callback;

import java.time.OffsetDateTime;

public record CallbackMetadata(
    String workerId,
    int attemptNo,
    String leaseOwner,
    String requestId,
    String traceId,
    OffsetDateTime timestamp,
    String nonce,
    String idempotencyKey,
    String signature,
    String urlPathWithQuery,
    String bodySha256
) {
}
