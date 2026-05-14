package com.meeting.api.app.speaker;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.speaker.CreateSpeakerEnrollmentCommand;
import com.meeting.api.client.speaker.CreateSpeakerProfileCommand;
import com.meeting.api.client.speaker.SpeakerEnrollmentDTO;
import com.meeting.api.client.speaker.SpeakerProfileDTO;
import com.meeting.api.client.speaker.SpeakerProfileFacade;
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
    private final TenantScopedTransaction tenantScopedTransaction;
    private final Clock clock;

    public SpeakerProfileApplicationService(
        SpeakerProfileRepository profileRepository,
        SpeakerEnrollmentRepository enrollmentRepository,
        TenantScopedTransaction tenantScopedTransaction
    ) {
        this(profileRepository, enrollmentRepository, tenantScopedTransaction, Clock.systemUTC());
    }

    public SpeakerProfileApplicationService(
        SpeakerProfileRepository profileRepository,
        SpeakerEnrollmentRepository enrollmentRepository,
        TenantScopedTransaction tenantScopedTransaction,
        Clock clock
    ) {
        this.profileRepository = profileRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.tenantScopedTransaction = tenantScopedTransaction;
        this.clock = clock;
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
        return profileRepository.findById(tenantId, profileId).map(SpeakerProfileApplicationService::toDto);
    }

    @Override
    public List<SpeakerProfileDTO> list(String tenantId) {
        return profileRepository.listByTenant(tenantId, false).stream()
            .map(SpeakerProfileApplicationService::toDto)
            .toList();
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
            log.info("speaker_profile_deleted tenant={} profile={} by={} reason={}", tenantId, profileId, deletedBy, reason);
            return null;
        });
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
        return enrollmentRepository.findByProfile(tenantId, profileId).stream()
            .map(SpeakerProfileApplicationService::toDto)
            .toList();
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
}
