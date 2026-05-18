package com.meeting.api.domain.breakglass;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.BreakGlassStatus;
import java.util.List;
import java.util.Optional;

/** Repository port for {@link BreakGlassRequest}. */
public interface BreakGlassRequestRepository {

    void save(BreakGlassRequest request);

    void update(BreakGlassRequest request);

    Optional<BreakGlassRequest> findById(String tenantId, String requestId);

    PageResult<BreakGlassRequest> listByTenant(
        String tenantId, BreakGlassStatus status, String cursor, int limit
    );

    /** Used by the expiry scanner — pulls APPROVED rows past their valid_until. */
    List<BreakGlassRequest> claimExpired(String tenantId, int limit);
}
