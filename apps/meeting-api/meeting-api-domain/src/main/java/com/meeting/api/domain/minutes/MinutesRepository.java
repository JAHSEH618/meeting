package com.meeting.api.domain.minutes;

import com.meeting.api.client.enums.StaleStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface MinutesRepository {
    Optional<MinutesRecord> findCurrent(String tenantId, String meetingId);

    int currentMinutesVersion(String tenantId, String meetingId);

    String save(MinutesRecord record);

    void incrementMeetingMinutesVersion(String tenantId, String meetingId, int newVersion);

    void markStale(String tenantId, String meetingId);

    record MinutesRecord(
        String id,
        String tenantId,
        String meetingId,
        int minutesVersion,
        int sourceTranscriptVersion,
        String title,
        String markdown,
        List<SectionRecord> sections,
        String status,
        StaleStatus staleStatus,
        String artifactManifestId,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
    }

    record SectionRecord(
        String type,
        String title,
        List<ItemRecord> items
    ) {
    }

    record ItemRecord(
        String text,
        List<EvidenceRecord> evidence
    ) {
    }

    record EvidenceRecord(
        String segmentId,
        Long startMs,
        Long endMs,
        String evidenceTextSnapshot
    ) {
    }
}
