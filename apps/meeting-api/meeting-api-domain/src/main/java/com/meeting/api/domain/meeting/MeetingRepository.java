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
}
