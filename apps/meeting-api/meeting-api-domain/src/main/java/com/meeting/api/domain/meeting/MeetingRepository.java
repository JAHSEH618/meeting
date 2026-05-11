package com.meeting.api.domain.meeting;

import java.util.List;
import java.util.Optional;

public interface MeetingRepository {
    Meeting save(Meeting meeting);

    Optional<Meeting> findById(String tenantId, String meetingId);

    List<Meeting> findByTenantId(String tenantId);
}
