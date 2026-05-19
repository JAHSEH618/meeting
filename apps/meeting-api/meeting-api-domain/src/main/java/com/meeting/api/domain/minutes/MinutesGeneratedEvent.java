package com.meeting.api.domain.minutes;

import com.meeting.api.domain.common.DomainEvent;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Emitted by {@code MinutesApplicationService.generateForTask / regenerate} after a new
 * minutes version is persisted. Consumed:
 * <ul>
 *   <li>in-process by {@code MinutesGeneratedRagIndexer} to re-chunk + re-embed
 *       the meeting (sourceType=MINUTES for the minutes body)</li>
 *   <li>via the outbox by downstream observers (analytics, finalize SSE)</li>
 * </ul>
 */
public record MinutesGeneratedEvent(
    String eventId,
    String tenantId,
    String meetingId,
    String minutesId,
    int minutesVersion,
    int transcriptVersion,
    long sequenceNo,
    OffsetDateTime occurredAt
) implements DomainEvent {
    @Override
    public String eventType() {
        return "MinutesGeneratedEvent";
    }

    @Override
    public String aggregateType() {
        return "Meeting";
    }

    @Override
    public String aggregateId() {
        return meetingId;
    }

    @Override
    public String payloadVersion() {
        return "v1";
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of(
            "meetingId", meetingId,
            "minutesId", minutesId,
            "minutesVersion", minutesVersion,
            "transcriptVersion", transcriptVersion
        );
    }
}
