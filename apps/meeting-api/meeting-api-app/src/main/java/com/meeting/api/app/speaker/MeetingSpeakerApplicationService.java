package com.meeting.api.app.speaker;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.speaker.MeetingSpeakerDTO;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
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
    private final TranscriptRepository transcriptRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    @Autowired
    public MeetingSpeakerApplicationService(
        MeetingSpeakerRepository meetingSpeakerRepository,
        SpeakerProfileRepository speakerProfileRepository,
        TranscriptRepository transcriptRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(meetingSpeakerRepository, speakerProfileRepository, transcriptRepository,
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
        this.meetingSpeakerRepository = meetingSpeakerRepository;
        this.speakerProfileRepository = speakerProfileRepository;
        this.transcriptRepository = transcriptRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }
    public List<MeetingSpeakerDTO> list(String tenantId, String meetingId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> meetingSpeakerRepository.findByMeeting(tenantId, meetingId).stream()
                .map(MeetingSpeakerApplicationService::toDto)
                .toList());
    }
    public void confirm(String tenantId, String meetingId, String speakerLabel,
                         String personId, String speakerProfileId, String confirmedBy) {
        if (personId == null || personId.isBlank()) {
            throw new IllegalArgumentException("personId is required");
        }
        tenantScopedTransaction.execute(tenantId, confirmedBy, null, () -> {
            if (speakerProfileId != null && !speakerProfileId.isBlank()) {
                SpeakerProfile profile = speakerProfileRepository.findById(tenantId, speakerProfileId)
                    .orElseThrow(() -> new IllegalArgumentException("speaker profile not found: " + speakerProfileId));
                if (!profile.isActive()) {
                    throw new IllegalStateException("speaker profile is not ACTIVE: " + speakerProfileId);
                }
                if (!profile.personId().equals(personId)) {
                    throw new IllegalArgumentException("personId does not match speaker profile");
                }
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            meetingSpeakerRepository.confirm(tenantId, meetingId, speakerLabel, personId, confirmedBy, now);
            String displayName = speakerProfileId == null ? personId
                : speakerProfileRepository.findById(tenantId, speakerProfileId)
                    .map(SpeakerProfile::displayNameSnapshot)
                    .orElse(personId);
            int updated = transcriptRepository.updateSpeakerForLabel(tenantId, meetingId, speakerLabel, personId, displayName, now);
            knowledgeChunkRepository.markStaleForMeeting(tenantId, meetingId);
            log.info("speaker_confirmed tenant={} meeting={} label={} segments={} person={}", tenantId, meetingId, speakerLabel, updated, personId);
            return null;
        });
    }
    public void reject(String tenantId, String meetingId, String speakerLabel, String rejectedBy) {
        tenantScopedTransaction.execute(tenantId, rejectedBy, null, () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            meetingSpeakerRepository.reject(tenantId, meetingId, speakerLabel, rejectedBy, now);
            log.info("speaker_rejected tenant={} meeting={} label={}", tenantId, meetingId, speakerLabel);
            return null;
        });
    }

    private static MeetingSpeakerDTO toDto(MeetingSpeakerRepository.MeetingSpeakerRecord r) {
        return new MeetingSpeakerDTO(
            r.speakerLabel(),
            r.globalSpeakerLabel(),
            r.confirmedPersonId(),
            null,
            r.verificationStatus(),
            r.autoMatchScore(),
            r.confirmedAt(),
            r.candidatePersonIds()
        );
    }
}
