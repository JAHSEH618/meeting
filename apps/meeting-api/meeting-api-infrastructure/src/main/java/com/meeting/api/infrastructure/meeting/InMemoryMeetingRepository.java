package com.meeting.api.infrastructure.meeting;

import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryMeetingRepository implements MeetingRepository {
    private final ConcurrentMap<String, Meeting> meetings = new ConcurrentHashMap<>();

    @Override
    public Meeting save(Meeting meeting) {
        meetings.put(key(meeting.tenantId(), meeting.id()), meeting);
        return meeting;
    }

    @Override
    public Optional<Meeting> findById(String tenantId, String meetingId) {
        return Optional.ofNullable(meetings.get(key(tenantId, meetingId)));
    }

    @Override
    public List<Meeting> findByTenantId(String tenantId) {
        return meetings.values().stream()
            .filter(meeting -> meeting.tenantId().equals(tenantId))
            .sorted(Comparator.comparing(Meeting::createdAt).reversed())
            .toList();
    }

    private String key(String tenantId, String meetingId) {
        return tenantId + ":" + meetingId;
    }
}
