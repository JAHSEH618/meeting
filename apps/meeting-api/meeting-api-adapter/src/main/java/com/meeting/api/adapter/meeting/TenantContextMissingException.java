package com.meeting.api.adapter.meeting;

import com.meeting.api.client.common.ErrorCode;

/**
 * Thrown when the tenant context is missing — mapped to 403 with TENANT_CONTEXT_MISSING
 * by the ControllerAdvice. See spec.md §2.1 rule 7.
 */
public class TenantContextMissingException extends RuntimeException {
    private final ErrorCode errorCode = ErrorCode.TENANT_CONTEXT_MISSING;

    public TenantContextMissingException(String message) {
        super(message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
