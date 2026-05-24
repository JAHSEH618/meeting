package com.meeting.api.app.speaker;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.speaker.CreateSpeakerEnrollmentCommand;
import com.meeting.api.client.speaker.CreateSpeakerProfileCommand;
import com.meeting.api.client.speaker.SpeakerEnrollmentDTO;
import com.meeting.api.client.speaker.SpeakerProfileDTO;
import com.meeting.api.client.speaker.SpeakerProfileFacade;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerEnrollmentRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SpeakerProfileApplicationService implements SpeakerProfileFacade {
    private static final Logger log = LoggerFactory.getLogger(SpeakerProfileApplicationService.class);

    private final SpeakerProfileRepository profileRepository;
    private final SpeakerEnrollmentRepository enrollmentRepository;
    private final SpeakerEmbeddingRepository embeddingRepository;
    private final MeetingSpeakerRepository meetingSpeakerRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    public SpeakerProfileApplicationService(
        SpeakerProfileRepository profileRepository,
        SpeakerEnrollmentRepository enrollmentRepository,
        SpeakerEmbeddingRepository embeddingRepository,
        MeetingSpeakerRepository meetingSpeakerRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(profileRepository, enrollmentRepository, embeddingRepository,
            meetingSpeakerRepository, knowledgeChunkRepository, tenantScopedTransaction, Clock.systemUTC());
    }

    public SpeakerProfileApplicationService(
        SpeakerProfileRepository profileRepository,
        SpeakerEnrollmentRepository enrollmentRepository,
        SpeakerEmbeddingRepository embeddingRepository,
        MeetingSpeakerRepository meetingSpeakerRepository,
        KnowledgeChunkRepository knowledgeChunkRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.profileRepository = profileRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.embeddingRepository = embeddingRepository;
        this.meetingSpeakerRepository = meetingSpeakerRepository;
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
    }

    /** Legacy constructor used by older tests; cascade dependencies default to no-op. */
    public SpeakerProfileApplicationService(
        SpeakerProfileRepository profileRepository,
        SpeakerEnrollmentRepository enrollmentRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this(profileRepository, enrollmentRepository,
            new NoOpEmbeddingRepo(), new NoOpMeetingSpeakerRepo(), new NoOpKnowledgeChunkRepo(),
            tenantScopedTransaction, clock);
    }

    @Override
    public SpeakerProfileDTO create(CreateSpeakerProfileCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.enrolledBy(), command.requestId(), () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            SpeakerProfile profile = SpeakerProfile.create(
                "spk_" + UUID.randomUUID().toString().replace("-", ""),
                command.tenantId(),
                command.personId(),
                command.displayName(),
                command.consentSource(),
                command.consentVersion(),
                command.enrolledBy(),
                now
            );
            SpeakerProfile saved = profileRepository.save(profile);
            log.info("speaker_profile_created tenant={} profile={} person={}", saved.tenantId(), saved.id(), saved.personId());
            return toDto(saved);
        });
    }

    @Override
    public Optional<SpeakerProfileDTO> get(String tenantId, String profileId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> profileRepository.findById(tenantId, profileId).map(SpeakerProfileApplicationService::toDto));
    }

    @Override
    public List<SpeakerProfileDTO> list(String tenantId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> profileRepository.listByTenant(tenantId, false).stream()
                .map(SpeakerProfileApplicationService::toDto)
                .toList());
    }

    @Override
    public void revoke(String tenantId, String profileId, String revokedBy, String reason) {
        tenantScopedTransaction.execute(tenantId, revokedBy, null, () -> {
            SpeakerProfile profile = profileRepository.findById(tenantId, profileId)
                .orElseThrow(() -> new IllegalArgumentException("speaker profile not found: " + profileId));
            OffsetDateTime now = OffsetDateTime.now(clock);
            profile.revoke(now);
            profileRepository.updateConsentStatus(tenantId, profileId, profile.consentStatus(),
                profile.revokedAt(), profile.deletedAt(), profile.updatedAt());
            cascadeOnRevocation(tenantId, profile, now, false);
            log.info("speaker_profile_revoked tenant={} profile={} by={} reason={}", tenantId, profileId, revokedBy, reason);
            return null;
        });
    }

    @Override
    public void delete(String tenantId, String profileId, String deletedBy, String reason) {
        tenantScopedTransaction.execute(tenantId, deletedBy, null, () -> {
            SpeakerProfile profile = profileRepository.findById(tenantId, profileId)
                .orElseThrow(() -> new IllegalArgumentException("speaker profile not found: " + profileId));
            OffsetDateTime now = OffsetDateTime.now(clock);
            profile.delete(now);
            profileRepository.updateConsentStatus(tenantId, profileId, profile.consentStatus(),
                profile.revokedAt(), profile.deletedAt(), profile.updatedAt());
            cascadeOnRevocation(tenantId, profile, now, true);
            log.info("speaker_profile_deleted tenant={} profile={} by={} reason={}", tenantId, profileId, deletedBy, reason);
            return null;
        });
    }

    private void cascadeOnRevocation(String tenantId, SpeakerProfile profile, OffsetDateTime now, boolean isDelete) {
        int embeddingsAffected = isDelete
            ? embeddingRepository.deleteForProfile(tenantId, profile.id(), now)
            : embeddingRepository.revokeForProfile(tenantId, profile.id(), now);
        List<String> meetingIds = meetingSpeakerRepository.findMeetingIdsByConfirmedPerson(tenantId, profile.personId());
        int meetingsStaled = 0;
        for (String meetingId : meetingIds) {
            knowledgeChunkRepository.markStaleForMeeting(tenantId, meetingId);
            meetingsStaled++;
        }
        log.info("speaker_revoke_cascade tenant={} profile={} delete={} embeddings={} meetingsStaled={}",
            tenantId, profile.id(), isDelete, embeddingsAffected, meetingsStaled);
    }

    @Override
    public SpeakerEnrollmentDTO addEnrollment(CreateSpeakerEnrollmentCommand command) {
        return tenantScopedTransaction.execute(command.tenantId(), command.createdBy(), command.requestId(), () -> {
            SpeakerProfile profile = profileRepository.findById(command.tenantId(), command.speakerProfileId())
                .orElseThrow(() -> new IllegalArgumentException("speaker profile not found: " + command.speakerProfileId()));
            if (!profile.isActive()) {
                throw new IllegalStateException("speaker profile is not ACTIVE: " + command.speakerProfileId());
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            String enrollmentId = "spe_" + UUID.randomUUID().toString().replace("-", "");
            var rec = new SpeakerEnrollmentRepository.SpeakerEnrollmentRecord(
                enrollmentId,
                command.tenantId(),
                command.speakerProfileId(),
                command.sourceAudioFileId(),
                "PENDING",
                null,
                null,
                null,
                null,
                command.createdBy(),
                now,
                now
            );
            enrollmentRepository.save(rec);
            return toDto(rec);
        });
    }

    @Override
    public List<SpeakerEnrollmentDTO> listEnrollments(String tenantId, String profileId) {
        return tenantScopedTransaction.execute(tenantId, null, null,
            () -> enrollmentRepository.findByProfile(tenantId, profileId).stream()
                .map(SpeakerProfileApplicationService::toDto)
                .toList());
    }

    private static SpeakerProfileDTO toDto(SpeakerProfile p) {
        return new SpeakerProfileDTO(
            p.id(), p.tenantId(), p.personId(), p.displayNameSnapshot(),
            p.consentStatus(), p.consentSource(), p.consentVersion(),
            p.revokedAt(), p.deletedAt(), p.createdAt(), p.updatedAt()
        );
    }

    private static SpeakerEnrollmentDTO toDto(SpeakerEnrollmentRepository.SpeakerEnrollmentRecord r) {
        return new SpeakerEnrollmentDTO(
            r.id(), r.speakerProfileId(), r.tenantId(), r.sourceAudioFileId(),
            r.enrollmentStatus(), r.qualityScore(), r.modelVersion(), r.errorCode(),
            r.createdAt(), r.updatedAt()
        );
    }

    private static final class NoOpEmbeddingRepo implements SpeakerEmbeddingRepository {
        @Override public void save(SpeakerEmbeddingRecord record) { }
        @Override public List<SpeakerEmbeddingRecord> findByProfile(String tenantId, String speakerProfileId) { return List.of(); }
        @Override public int revokeForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) { return 0; }
        @Override public int deleteForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) { return 0; }
    }

    private static final class NoOpMeetingSpeakerRepo implements MeetingSpeakerRepository {
        @Override public Optional<MeetingSpeakerRecord> find(String tenantId, String meetingId, String speakerLabel) { return Optional.empty(); }
        @Override public List<MeetingSpeakerRecord> findByMeeting(String tenantId, String meetingId) { return List.of(); }
        @Override public List<String> findMeetingIdsByConfirmedPerson(String tenantId, String personId) { return List.of(); }
        @Override public void saveCandidates(String tenantId, String meetingId, String speakerLabel, List<String> candidatePersonIds, Double autoMatchScore, String matchSource, OffsetDateTime now) { }
        @Override public void confirm(String tenantId, String meetingId, String speakerLabel, String confirmedPersonId, String confirmedBy, OffsetDateTime now) { }
        @Override public void reject(String tenantId, String meetingId, String speakerLabel, String rejectedBy, OffsetDateTime now) { }
    }

    private static final class NoOpKnowledgeChunkRepo implements KnowledgeChunkRepository {
        @Override public int markStaleForMeeting(String tenantId, String meetingId) { return 0; }
    }
}
