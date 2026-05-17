package com.meeting.api.domain.compliance;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.LegalHoldScopeType;
import java.util.Optional;

/**
 * Repository port for {@link LegalHold}. Domain stays free of JDBC; the
 * implementation lives in {@code meeting-api-infrastructure}.
 */
public interface LegalHoldRepository {

    void save(LegalHold hold);

    void update(LegalHold hold);

    Optional<LegalHold> findById(String tenantId, String holdId);

    /**
     * @return the active hold (status=ACTIVE) protecting the given
     *         {@code (tenantId, scopeType, scopeId)} tuple, or empty
     *         if none exists. {@link LegalHoldCheckPort} delegates here.
     */
    Optional<LegalHold> findActive(String tenantId, LegalHoldScopeType scopeType, String scopeId);

    PageResult<LegalHold> listByTenant(String tenantId, String cursor, int limit);
}
