package com.meeting.api.client.breakglass;

import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.BreakGlassStatus;
import java.util.Optional;

public interface BreakGlassFacade {

    BreakGlassRequestDTO create(CreateBreakGlassCommand command);

    Optional<BreakGlassRequestDTO> get(String tenantId, String requestId);

    PageResult<BreakGlassRequestDTO> list(
        String tenantId, BreakGlassStatus status, String cursor, int limit
    );

    BreakGlassRequestDTO approve(
        String tenantId, String requestId, String approverId
    );

    BreakGlassRequestDTO reject(
        String tenantId, String requestId, String approverId, String reason
    );
}
