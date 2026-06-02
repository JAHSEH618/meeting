package com.meeting.api.domain.speaker;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Per-meeting speaker label aggregate ({@code meeting_speakers}).
 * Tracks anonymous speaker labels surfaced by diarization, the worker-suggested
 * candidate profile list, and any user-confirmed person.
 */
public interface MeetingSpeakerRepository {
    Optional<MeetingSpeakerRecord> find(String tenantId, String meetingId, String speakerLabel);

    List<MeetingSpeakerRecord> findByMeeting(String tenantId, String meetingId);

    /** Lookup meetings that have a confirmed person. Used by revoke cascade. */
    List<String> findMeetingIdsByConfirmedPerson(String tenantId, String personId);

    /** Upsert per (tenant, meeting, speakerLabel). */
    void saveCandidates(String tenantId, String meetingId, String speakerLabel,
                         List<String> candidatePersonIds, Double autoMatchScore, String matchSource,
                         OffsetDateTime now);

    /** Upsert per (tenant, meeting, speakerLabel) with full candidate profile metadata. */
    default void saveCandidates(String tenantId, String meetingId, String speakerLabel,
                         List<String> candidatePersonIds, List<SpeakerCandidate> candidates,
                         Double autoMatchScore, String matchSource, OffsetDateTime now) {
        saveCandidates(tenantId, meetingId, speakerLabel, candidatePersonIds, autoMatchScore, matchSource, now);
    }

    /** Persist the user's confirmation decision. */
    void confirm(String tenantId, String meetingId, String speakerLabel,
                  String confirmedPersonId, String confirmedBy, OffsetDateTime now);

    void reject(String tenantId, String meetingId, String speakerLabel,
                 String rejectedBy, OffsetDateTime now);

    record MeetingSpeakerRecord(
        String id,
        String tenantId,
        String meetingId,
        String speakerLabel,
        String globalSpeakerLabel,
        List<String> candidatePersonIds,
        List<SpeakerCandidate> candidates,
        Double autoMatchScore,
        String matchSource,
        String verificationStatus,
        String confirmedPersonId,
        String confirmedBy,
        OffsetDateTime confirmedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
    ) {
        public MeetingSpeakerRecord(
            String id,
            String tenantId,
            String meetingId,
            String speakerLabel,
            String globalSpeakerLabel,
            List<String> candidatePersonIds,
            Double autoMatchScore,
            String matchSource,
            String verificationStatus,
            String confirmedPersonId,
            String confirmedBy,
            OffsetDateTime confirmedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
        ) {
            this(
                id,
                tenantId,
                meetingId,
                speakerLabel,
                globalSpeakerLabel,
                candidatePersonIds,
                List.of(),
                autoMatchScore,
                matchSource,
                verificationStatus,
                confirmedPersonId,
                confirmedBy,
                confirmedAt,
                createdAt,
                updatedAt
            );
        }
    }

    record SpeakerCandidate(
        String personId,
        String speakerProfileId,
        Double confidence
    ) {
    }
}
