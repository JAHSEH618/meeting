package com.meeting.api.domain.llm;

import com.meeting.api.client.enums.SecurityLevel;

/**
 * Thrown when an LLM-bound capability is invoked on a meeting whose
 * {@link SecurityLevel} is blocked by the data-boundary policy.
 *
 * Maps to {@code SECURITY_LEVEL_BLOCKED} HTTP 422 in adapter layer.
 */
public final class SecurityLevelBlockedException extends RuntimeException {
    private final SecurityLevel securityLevel;
    private final String blockedCapability;

    public SecurityLevelBlockedException(SecurityLevel securityLevel, String blockedCapability) {
        super("LLM capability blocked for security level " + securityLevel + ": " + blockedCapability);
        this.securityLevel = securityLevel;
        this.blockedCapability = blockedCapability;
    }

    public SecurityLevel securityLevel() {
        return securityLevel;
    }

    public String blockedCapability() {
        return blockedCapability;
    }
}
