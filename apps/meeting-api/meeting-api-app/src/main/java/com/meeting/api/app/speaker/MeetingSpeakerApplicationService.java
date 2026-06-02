package com.meeting.api.app.speaker;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.transcript.TranscriptApplicationService;
import com.meeting.api.client.speaker.MeetingSpeakerCandidateDTO;
import com.meeting.api.client.speaker.MeetingSpeakerDTO;
import com.meeting.api.domain.person.Person;
import com.meeting.api.domain.person.PersonRepository;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository.MeetingSpeakerRecord;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository.SpeakerCandidate;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import com.meeting.api.domain.transcript.TranscriptRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * User-facing speaker confirmation flow.
 *
 * <p>{@link #confirm} verifies the chosen speaker profile is authorized for the tenant,
 * marks the {@code meeting_speakers} row CONFIRMED, bulk-updates transcript segments
 * carrying the same speaker label, and STALEs downstream RAG chunks (the meeting context
 * changed, so chunks should be rebuilt). {@link #reject} marks the row REJECTED without
 * touching the transcript.</p>
 */
@Service
public class MeetingSpeakerApplicationService {
    private static final Logger log = LoggerFactory.getLogger(MeetingSpeakerApplicationService.class);

    private final MeetingSpeakerRepository meetingSpeakerRepository;
    private final SpeakerProfileRepository speakerProfileRepository;
    private final PersonRepository personRepository;
    private final TranscriptRepository transcriptRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    @Autowired
    public MeetingSpeakerApplicationService(
        MeetingSpeakerRepository meetingSpeakerRepository,
        SpeakerProfileRepository speakerProfileRepository,
        PersonRepository personRepository,
        TranscriptRepository transcriptRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(meetingSpeakerRepository, speakerProfileRepository, personRepository, transcriptRepository,
            knowledgeChunkRepository, tenantScopedTransaction, Clock.systemUTC());
    }

    public MeetingSpeakerApplicationService(
        MeetingSpeakerRepository meetingSpeakerRepository,
        SpeakerProfileRepository speakerProfileRepository,
        TranscriptRepository transcriptRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this(meetingSpeakerRepository, speakerProfileRepository, null, transcriptRepository,
            knowledgeChunkRepository, tenantScopedTransaction, clock);
    }

    public MeetingSpeakerApplicationService(
        MeetingSpeakerRepository meetingSpeakerRepository,
        SpeakerProfileRepository speakerProfileRepository,
        PersonRepository personRepository,
        TranscriptRepository transcriptRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.meetingSpeakerRepository = meetingSpeakerRepository;
        this.speakerProfileRepository = speakerProfileRepository;
        this.personRepository = personRepository;
        this.transcriptRepository = transcriptRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    public List<MeetingSpeakerDTO> list(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> meetingSpeakerRepository.findByMeeting(tenantId, meetingId).stream()
                .map(record -> toDto(tenantId, record))
                .toList());
    }

    public void confirm(String tenantId, String meetingId, String speakerLabel,
                         String personId, String speakerProfileId, String confirmedBy) {
        confirmInternal(tenantId, meetingId, speakerLabel, personId, speakerProfileId, null, confirmedBy, false);
    }

    public void confirm(String tenantId, String meetingId, String speakerLabel,
                         String personId, String speakerProfileId, Integer expectedTranscriptVersion, String confirmedBy) {
        confirmInternal(tenantId, meetingId, speakerLabel, personId, speakerProfileId, expectedTranscriptVersion, confirmedBy, true);
    }

    private void confirmInternal(String tenantId, String meetingId, String speakerLabel,
                                 String personId, String speakerProfileId, Integer expectedTranscriptVersion,
                                 String confirmedBy, boolean requireExpectedVersion) {
        if (personId == null || personId.isBlank()) {
            throw new IllegalArgumentException("personId is required");
        }
        if (requireExpectedVersion && expectedTranscriptVersion == null) {
            throw new IllegalArgumentException("expectedTranscriptVersion is required");
        }
        tenantScopedTransaction.execute(tenantId, confirmedBy, null, () -> {
            int currentTranscriptVersion = transcriptRepository.currentTranscriptVersion(tenantId, meetingId);
            int effectiveExpectedVersion = expectedTranscriptVersion == null ? currentTranscriptVersion : expectedTranscriptVersion;
            if (effectiveExpectedVersion != currentTranscriptVersion) {
                throw new TranscriptApplicationService.TranscriptVersionConflictException(
                    currentTranscriptVersion,
                    effectiveExpectedVersion
                );
            }
            SpeakerProfile profile = null;
            if (speakerProfileId != null && !speakerProfileId.isBlank()) {
                profile = speakerProfileRepository.findById(tenantId, speakerProfileId)
                    .orElseThrow(() -> new IllegalArgumentException("speaker profile not found: " + speakerProfileId));
                if (!profile.isActive()) {
                    throw new IllegalStateException("speaker profile is not ACTIVE: " + speakerProfileId);
                }
                if (!profile.personId().equals(personId)) {
                    throw new IllegalArgumentException("personId does not match speaker profile");
                }
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            meetingSpeakerRepository.confirm(tenantId, meetingId, speakerLabel, personId, speakerProfileId, confirmedBy, now);
            String displayName = resolveDisplayName(tenantId, personId, profile);
            int updated = transcriptRepository.updateSpeakerForLabel(tenantId, meetingId, speakerLabel, personId, displayName, now);
            knowledgeChunkRepository.markStaleForMeeting(tenantId, meetingId);
            log.info("speaker_confirmed tenant={} meeting={} label={} segments={} person={}", tenantId, meetingId, speakerLabel, updated, personId);
            return null;
        });
    }

    public void reject(String tenantId, String meetingId, String speakerLabel, String reason, String rejectedBy) {
        if (!hasText(reason)) {
            throw new IllegalArgumentException("reason is required");
        }
        tenantScopedTransaction.execute(tenantId, rejectedBy, null, () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            meetingSpeakerRepository.reject(tenantId, meetingId, speakerLabel, rejectedBy, now);
            log.info("speaker_rejected tenant={} meeting={} label={} reason={}",
                tenantId, meetingId, speakerLabel, reason);
            return null;
        });
    }

    private MeetingSpeakerDTO toDto(String tenantId, MeetingSpeakerRecord r) {
        return new MeetingSpeakerDTO(
            r.speakerLabel(),
            hasText(r.confirmedPersonId()) ? resolveDisplayName(tenantId, r.confirmedPersonId(), null) : r.globalSpeakerLabel(),
            r.confirmedPersonId(),
            r.confirmedSpeakerProfileId(),
            confirmationStatus(r),
            r.autoMatchScore(),
            r.confirmedAt(),
            toCandidateDtos(tenantId, r.candidates())
        );
    }

    private List<MeetingSpeakerCandidateDTO> toCandidateDtos(String tenantId, List<SpeakerCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
            .map(candidate -> toCandidateDto(tenantId, candidate))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private MeetingSpeakerCandidateDTO toCandidateDto(String tenantId, SpeakerCandidate candidate) {
        if (candidate == null
            || !hasText(candidate.personId())
            || !hasText(candidate.speakerProfileId())) {
            return null;
        }
        SpeakerProfile profile = speakerProfileRepository.findById(tenantId, candidate.speakerProfileId()).orElse(null);
        if (profile == null || !profile.isActive() || !candidate.personId().equals(profile.personId())) {
            return null;
        }
        return new MeetingSpeakerCandidateDTO(
            candidate.personId(),
            candidate.speakerProfileId(),
            resolveDisplayName(tenantId, candidate.personId(), profile),
            candidate.confidence()
        );
    }

    private static String confirmationStatus(MeetingSpeakerRecord r) {
        if ("CONFIRMED".equals(r.verificationStatus())) {
            return SpeakerAutoConfirmService.AUTO_CONFIRM_ACTOR.equals(r.confirmedBy())
                ? "AUTO_CONFIRMED"
                : "MANUALLY_CONFIRMED";
        }
        return r.verificationStatus();
    }

    private String resolveDisplayName(String tenantId, String personId, SpeakerProfile profile) {
        if (profile != null && hasText(profile.displayNameSnapshot())) {
            return profile.displayNameSnapshot();
        }
        if (personRepository != null && hasText(personId)) {
            return personRepository.findById(tenantId, personId)
                .map(Person::displayName)
                .filter(MeetingSpeakerApplicationService::hasText)
                .orElse(personId);
        }
        return hasText(personId) ? personId : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
