package com.meeting.api.domain.meeting;

import com.meeting.api.client.enums.DocumentRole;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Workstation D1 — link table between meetings and tenant documents.
 * Documents themselves are tenant-scoped (one row in `documents`) and may be
 * referenced by multiple meetings.
 */
public interface MeetingDocumentRepository {
    String save(MeetingDocumentRecord record);

    Optional<MeetingDocumentRecord> findActive(String tenantId, String meetingId, String documentId);

    /**
     * Returns active (non-deleted) links for the given meeting, joined with the
     * underlying document's title + security level so callers don't need a
     * second round-trip per row.
     */
    List<MeetingDocumentJoinRow> listByMeeting(String tenantId, String meetingId);

    /**
     * Soft-delete the active link if any. Returns true when one row was affected.
     */
    boolean softDelete(String tenantId, String meetingId, String documentId, OffsetDateTime now);

    record MeetingDocumentRecord(
        String id,
        String tenantId,
        String meetingId,
        String documentId,
        DocumentRole role,
        String attachedBy,
        OffsetDateTime attachedAt
    ) {
    }

    record MeetingDocumentJoinRow(
        String linkId,
        String meetingId,
        String documentId,
        String documentTitle,
        DocumentRole role,
        String attachedBy,
        OffsetDateTime attachedAt
    ) {
    }
}
