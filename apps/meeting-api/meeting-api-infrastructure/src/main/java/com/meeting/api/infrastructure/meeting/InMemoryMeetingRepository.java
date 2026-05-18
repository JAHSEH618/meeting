package com.meeting.api.infrastructure.meeting;

import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test helper repository. Runtime wiring uses JdbcMeetingRepository.
 */
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
        if (m != null && m.tenantId().equals(tenantId) && m.status() != MeetingStatus.DELETED) {
            return Optional.of(m);
        }
        return Optional.empty();
    }

    @Override
    public List<Meeting> findByTenantId(String tenantId) {
        return store.values().stream()
            .filter(m -> m.tenantId().equals(tenantId))
            .filter(m -> m.status() != MeetingStatus.DELETED)
            .toList();
    }

    @Override
    public void updateStatus(String tenantId, String meetingId, MeetingStatus status) {
        store.computeIfPresent(meetingId, (id, meeting) -> {
            if (!meeting.tenantId().equals(tenantId)) {
                return meeting;
            }
            return new Meeting.Builder()
                .id(meeting.id())
                .tenantId(meeting.tenantId())
                .title(meeting.title())
                .securityLevel(meeting.securityLevel())
                .status(status)
                .language(meeting.language())
                .transcriptVersion(meeting.transcriptVersion())
                .minutesVersion(meeting.minutesVersion())
                .createdAt(meeting.createdAt())
                .createdBy(meeting.createdBy())
                .participants(meeting.participants())
                .build();
        });
    }

    @Override
    public boolean markDeleted(String tenantId, String meetingId) {
        Meeting existing = store.get(meetingId);
        if (existing == null
            || !existing.tenantId().equals(tenantId)
            || existing.status() == MeetingStatus.DELETED) {
            return false;
        }
        store.put(meetingId, existing.markDeleted());
        return true;
    }
}
