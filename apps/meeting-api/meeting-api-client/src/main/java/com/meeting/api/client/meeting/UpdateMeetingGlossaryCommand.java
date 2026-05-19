package com.meeting.api.client.meeting;

import java.util.List;

public record UpdateMeetingGlossaryCommand(
    String tenantId,
    String meetingId,
    List<GlossaryTermDTO> terms,
    String actorUserId,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
