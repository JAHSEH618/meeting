package com.meeting.api.domain.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Workstation D2 — read/write the meetings.glossary_terms jsonb column.
 * Returns raw terms; the application layer applies validation.
 */
public interface MeetingGlossaryRepository {
    Optional<List<GlossaryTerm>> findByMeetingId(String tenantId, String meetingId);

    /** Replace the glossary with the given terms and return the new updated_at. */
    OffsetDateTime replace(String tenantId, String meetingId, List<GlossaryTerm> terms, OffsetDateTime now);

    record GlossaryTerm(String term, String definition, List<String> aliases) {
    }
}
