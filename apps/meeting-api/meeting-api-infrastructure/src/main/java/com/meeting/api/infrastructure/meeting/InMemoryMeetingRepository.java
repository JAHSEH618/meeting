package com.meeting.api.infrastructure.meeting;

import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory repository for development / testing.
 * Replace with MyBatis-Plus + PostgreSQL implementation in production.
 */
@Repository
public class InMemoryMeetingRepository implements MeetingRepository {
    private final Map<String, Meeting> store = new ConcurrentHashMap<>();

    @Override
    public Meeting save(Meeting meeting) {
        store.put(meeting.id(), meeting);
        return meeting;
    }

    @Override
    public Optional<Meeting> findById(String tenantId, String meetingId) {
        Meeting m = store.get(meetingId);
        if (m != null && m.tenantId().equals(tenantId)) {
            return Optional.of(m);
        }
        return Optional.empty();
    }

    @Override
    public List<Meeting> findByTenantId(String tenantId) {
        return store.values().stream()
            .filter(m -> m.tenantId().equals(tenantId))
            .toList();
    }
}
