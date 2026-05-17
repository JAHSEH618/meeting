package com.meeting.api.client.export;

import com.meeting.api.client.common.PageResult;
import java.util.Optional;

/**
 * Application-layer facade for the export endpoints. The controller
 * is intentionally a thin shell that only translates HTTP to
 * commands.
 */
public interface ExportFacade {

    ExportJobDTO create(CreateExportCommand command);

    Optional<ExportJobDTO> get(String tenantId, String exportId);

    PageResult<ExportJobDTO> listByMeeting(
        String tenantId, String meetingId, String cursor, int limit
    );

    void cancel(String tenantId, String exportId, String userId);

    void revokeLink(String tenantId, String exportId, String userId);
}
