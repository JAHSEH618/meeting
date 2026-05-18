package com.meeting.api.domain.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import java.util.List;
import java.util.Optional;

public interface MeetingRepository {
    Meeting save(Meeting meeting);

    Optional<Meeting> findById(String tenantId, String meetingId);

    List<Meeting> findByTenantId(String tenantId);

    default void updateStatus(String tenantId, String meetingId, MeetingStatus status) {
        throw new UnsupportedOperationException("updateStatus is not implemented");
    }

    /**
     * Soft-delete the meeting: set {@code status='DELETED'} and {@code deleted_at=now()}.
     * Returns {@code true} when one row was affected (i.e. the meeting existed and
     * was not already soft-deleted), {@code false} otherwise.
     *
     * <p>The application layer uses a {@code false} result to translate to
     * {@code MEETING_NOT_FOUND} (404). Callers are responsible for legal-hold
     * and audit invariants before calling.
     */
    default boolean markDeleted(String tenantId, String meetingId) {
        throw new UnsupportedOperationException("markDeleted is not implemented");
    }
}
