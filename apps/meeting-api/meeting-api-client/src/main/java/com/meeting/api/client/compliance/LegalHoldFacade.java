package com.meeting.api.client.compliance;

import com.meeting.api.client.common.PageResult;
import java.util.Optional;

/**
 * Application-layer facade for {@code /api/legal-holds*}.
 */
public interface LegalHoldFacade {

    LegalHoldDTO create(CreateLegalHoldCommand command);

    Optional<LegalHoldDTO> get(String tenantId, String legalHoldId);

    PageResult<LegalHoldDTO> list(String tenantId, String cursor, int limit);

    void release(
        String tenantId, String legalHoldId,
        String releasedBy, String releaseReason
    );
}
