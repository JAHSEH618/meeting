package com.meeting.api.client.speaker;

import java.time.OffsetDateTime;
import java.util.List;

public record SpeakerProfileListDTO(
    List<Item> items,
    PageInfo page
) {
    public record Item(
        String speakerProfileId,
        String personId,
        String displayName,
        String status,
        Integer enrollmentCount,
        OffsetDateTime lastEnrolledAt
    ) {
    }

    public record PageInfo(
        String cursor,
        boolean hasMore,
        int limit
    ) {
    }
}
