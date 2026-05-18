package com.meeting.api.client.audit;

import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import java.time.OffsetDateTime;
import java.util.Map;

/** DTO surface for one audit event row in the admin query API. */
public record AuditEventDTO(
    String auditEventId,
    String actorUserId,
    String actorType,
    AuditAction action,
    String resourceType,
    String resourceId,
    AuditResult result,
    String reason,
    String traceId,
    Map<String, Object> payload,
    OffsetDateTime createdAt
) {}
