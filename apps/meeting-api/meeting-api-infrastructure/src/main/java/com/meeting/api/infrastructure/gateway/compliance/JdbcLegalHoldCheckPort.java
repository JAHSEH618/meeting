package com.meeting.api.infrastructure.gateway.compliance;

import com.meeting.api.client.enums.LegalHoldScopeType;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import com.meeting.api.domain.compliance.LegalHoldRepository;
import org.springframework.stereotype.Component;

/**
 * JDBC-backed {@link LegalHoldCheckPort} — Phase 7 swaps in this
 * implementation in place of {@code NoOpLegalHoldCheckPort} (which
 * remains as a {@code @ConditionalOnMissingBean} fallback for
 * environments that opted out of compliance).
 *
 * <p>The lookup is one indexed SQL call; the repository's
 * {@code findActive} method is the only contact with the database.
 * No caching layer is added here — Phase 8 can introduce a Caffeine
 * cache with explicit invalidation when a hold is placed / released.
 */
@Component
public class JdbcLegalHoldCheckPort implements LegalHoldCheckPort {

    private final LegalHoldRepository repository;

    public JdbcLegalHoldCheckPort(LegalHoldRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean isProtected(String tenantId, String scopeType, String scopeId) {
        LegalHoldScopeType type;
        try {
            type = LegalHoldScopeType.valueOf(scopeType);
        } catch (IllegalArgumentException ex) {
            // Unknown scope type: fail-safe (treat as not protected). Logged
            // upstream by the caller for visibility.
            return false;
        }
        return repository.findActive(tenantId, type, scopeId).isPresent();
    }
}
