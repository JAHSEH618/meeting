package com.meeting.api.client.meeting;

/** Workstation D1 — detach (soft-delete) the link, document itself remains. */
public record DetachMeetingDocumentCommand(
    String tenantId,
    String meetingId,
    String documentId,
    String actorUserId,
    String requestId,
    String traceId
) {
}
