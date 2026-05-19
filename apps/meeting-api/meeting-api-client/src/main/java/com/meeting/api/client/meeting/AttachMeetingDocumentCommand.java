package com.meeting.api.client.meeting;

import com.meeting.api.client.enums.DocumentRole;

/** Workstation D1 — attach a tenant document to a meeting under a role. */
public record AttachMeetingDocumentCommand(
    String tenantId,
    String meetingId,
    String documentId,
    DocumentRole role,
    String actorUserId,
    String idempotencyKey,
    String requestId,
    String traceId
) {
}
