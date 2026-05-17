package com.meeting.api.infrastructure.gateway.compliance;

import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default {@link LegalHoldCheckPort} for phase 1—6. Returns {@code false}
 * unconditionally. Phase 7 replaces this with a JDBC-backed implementation
 * that queries {@code legal_holds} and removes the
 * {@link ConditionalOnMissingBean} guard automatically.
 */
@Component
@ConditionalOnMissingBean(LegalHoldCheckPort.class)
public class NoOpLegalHoldCheckPort implements LegalHoldCheckPort {

    @Override
    public boolean isProtected(String tenantId, String scopeType, String scopeId) {
        return false;
    }
}
