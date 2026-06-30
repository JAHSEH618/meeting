package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.speaker.SpeakerProfileApplicationService;
import com.meeting.api.app.task.ProcessingTaskApplicationService;
import com.meeting.api.domain.rag.KnowledgeChunkRepository;
import com.meeting.api.client.speaker.CreateSpeakerEnrollmentCommand;
import com.meeting.api.client.speaker.CreateSpeakerProfileCommand;
import com.meeting.api.client.task.ProcessingTaskDTO;
import com.meeting.api.domain.speaker.MeetingSpeakerRepository;
import com.meeting.api.domain.speaker.SpeakerEmbeddingRepository;
import com.meeting.api.domain.speaker.SpeakerEnrollmentRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
import com.meeting.api.domain.storage.MeetingFile;
import com.meeting.api.domain.storage.MeetingFileRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeakerProfileApplicationServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-15T08:00:00Z");

    @Test
    void createPersistsProfileWithActiveConsentAndStableId() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        InMemoryEnrollmentRepo enrollments = new InMemoryEnrollmentRepo();
        var service = service(profiles, enrollments);

        var dto = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "Alice", "MEETING_INVITE", "v1", "user_01", "req_01", "trace_01", "idem_01"
        ));

        assertThat(dto.consentStatus()).isEqualTo("ACTIVE");
        assertThat(dto.tenantId()).isEqualTo("tenant_01");
        assertThat(dto.speakerProfileId()).startsWith("spk_");
        assertThat(profiles.store).containsKey(dto.speakerProfileId());
    }

    @Test
    void revokeMovesProfileToRevokedAndDeniesEnrollment() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        InMemoryEnrollmentRepo enrollments = new InMemoryEnrollmentRepo();
        var service = service(profiles, enrollments);
        var created = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "Alice", "INVITE", "v1", "user_01", "req_01", "trace_01", "idem_01"
        ));

        service.revoke("tenant_01", created.speakerProfileId(), "user_01", "user_request");

        assertThatThrownBy(() -> service.addEnrollment(new CreateSpeakerEnrollmentCommand(
            "tenant_01", created.speakerProfileId(), "file_01", "user_01", "req", "trace", "idem"
        ))).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ACTIVE");

        var afterRevoke = service.get("tenant_01", created.speakerProfileId()).orElseThrow();
        assertThat(afterRevoke.consentStatus()).isEqualTo("REVOKED");
        assertThat(afterRevoke.revokedAt()).isEqualTo(NOW);
    }

    @Test
    void revokeIsIdempotentForAlreadyRevokedProfile() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        var service = service(profiles, new InMemoryEnrollmentRepo());
        var created = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "Alice", "INVITE", "v1", "user_01", "req_01", "trace_01", "idem_01"
        ));

        service.revoke("tenant_01", created.speakerProfileId(), "user_01", "first");
        service.revoke("tenant_01", created.speakerProfileId(), "user_01", "duplicate");

        var dto = service.get("tenant_01", created.speakerProfileId()).orElseThrow();
        assertThat(dto.consentStatus()).isEqualTo("REVOKED");
        assertThat(dto.revokedAt()).isEqualTo(NOW);
        assertThat(profiles.consentUpdateCalls).isEqualTo(1);
    }

    @Test
    void deleteIsIdempotent() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        var service = service(profiles, new InMemoryEnrollmentRepo());
        var created = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "Alice", "INVITE", "v1", "user_01", "req_01", "trace_01", "idem_01"
        ));

        service.delete("tenant_01", created.speakerProfileId(), "user_01", "reason");
        service.delete("tenant_01", created.speakerProfileId(), "user_01", "reason");

        var dto = service.get("tenant_01", created.speakerProfileId()).orElseThrow();
        assertThat(dto.consentStatus()).isEqualTo("DELETED");
        assertThat(dto.deletedAt()).isNotNull();
    }

    @Test
    void addEnrollmentRecordsPendingStatusForActiveProfile() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        InMemoryEnrollmentRepo enrollments = new InMemoryEnrollmentRepo();
        var service = service(profiles, enrollments);
        var created = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "Alice", "INVITE", "v1", "user_01", "req_01", "trace_01", "idem_01"
        ));

        var enrollment = service.addEnrollment(new CreateSpeakerEnrollmentCommand(
            "tenant_01", created.speakerProfileId(), "file_01", "user_01", "req", "trace", "idem"
        ));

        assertThat(enrollment.enrollmentStatus()).isEqualTo("PENDING");
        assertThat(enrollment.sourceAudioFileId()).isEqualTo("file_01");
        assertThat(service.listEnrollments("tenant_01", created.speakerProfileId())).hasSize(1);
    }

    @Test
    void addEnrollmentDoesNotLeavePendingEnrollmentWhenTaskCreationFails() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        InMemoryEnrollmentRepo enrollments = new InMemoryEnrollmentRepo();
        var service = service(
            profiles,
            enrollments,
            new FailingProcessingTaskService(),
            new SingleMeetingFileRepository()
        );
        var created = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "Alice", "INVITE", "v1", "user_01", "req_01", "trace_01", "idem_01"
        ));

        assertThatThrownBy(() -> service.addEnrollment(new CreateSpeakerEnrollmentCommand(
            "tenant_01", created.speakerProfileId(), "file_01", "user_01", "req", "trace", "idem"
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("simulated task creation failure");

        assertThat(service.listEnrollments("tenant_01", created.speakerProfileId())).isEmpty();
    }

    @Test
    void listOnlyReturnsActiveProfiles() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        var service = service(profiles, new InMemoryEnrollmentRepo());
        var p1 = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "A", "INVITE", "v1", "u", "r", "t", "i1"
        ));
        var p2 = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_02", "B", "INVITE", "v1", "u", "r", "t", "i2"
        ));
        service.revoke("tenant_01", p2.speakerProfileId(), "u", "x");

        var page = service.list("tenant_01", null);
        assertThat(page.items()).extracting("speakerProfileId").containsExactly(p1.speakerProfileId());
    }

    @Test
    void listForPersonOnlyReturnsThatPersonsActiveProfiles() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        var service = service(profiles, new InMemoryEnrollmentRepo());
        var p1Active = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "A", "INVITE", "v1", "u", "r", "t", "i1"
        ));
        var p2Active = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_02", "B", "INVITE", "v1", "u", "r", "t", "i2"
        ));
        var p1Revoked = service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "A old", "INVITE", "v1", "u", "r", "t", "i3"
        ));
        service.revoke("tenant_01", p1Revoked.speakerProfileId(), "u", "x");

        var page = service.list("tenant_01", "person_01");

        assertThat(page.items()).extracting("speakerProfileId").containsExactly(p1Active.speakerProfileId());
        assertThat(page.items()).extracting("speakerProfileId")
            .doesNotContain(p2Active.speakerProfileId(), p1Revoked.speakerProfileId());
        assertThat(page.page().hasMore()).isFalse();
        assertThat(page.page().limit()).isEqualTo(1);
    }

    @Test
    void listForMissingPersonReturnsEmptyPage() {
        InMemoryProfileRepo profiles = new InMemoryProfileRepo();
        var service = service(profiles, new InMemoryEnrollmentRepo());
        service.create(new CreateSpeakerProfileCommand(
            "tenant_01", "person_01", "A", "INVITE", "v1", "u", "r", "t", "i1"
        ));

        var page = service.list("tenant_01", "missing_person");

        assertThat(page.items()).isEmpty();
        assertThat(page.page().limit()).isZero();
    }

    private static SpeakerProfileApplicationService service(InMemoryProfileRepo profiles, InMemoryEnrollmentRepo enrollments) {
        return new SpeakerProfileApplicationService(
            profiles, enrollments, TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static SpeakerProfileApplicationService service(
        InMemoryProfileRepo profiles,
        InMemoryEnrollmentRepo enrollments,
        ProcessingTaskApplicationService processingTasks,
        MeetingFileRepository meetingFiles
    ) {
        return new SpeakerProfileApplicationService(
            profiles,
            enrollments,
            new NoOpEmbeddingRepo(),
            new NoOpMeetingSpeakerRepo(),
            new NoOpKnowledgeChunkRepo(),
            TenantScopedTransaction.immediate(),
            processingTasks,
            meetingFiles,
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static final class InMemoryProfileRepo implements SpeakerProfileRepository {
        private final Map<String, SpeakerProfile> store = new LinkedHashMap<>();
        private int consentUpdateCalls;

        @Override
        public SpeakerProfile save(SpeakerProfile profile) {
            store.put(profile.id(), profile);
            return profile;
        }

        @Override
        public Optional<SpeakerProfile> findById(String tenantId, String profileId) {
            return Optional.ofNullable(store.get(profileId)).filter(p -> tenantId.equals(p.tenantId()));
        }

        @Override
        public List<SpeakerProfile> listByTenant(String tenantId, boolean includeRevoked) {
            return store.values().stream()
                .filter(p -> tenantId.equals(p.tenantId()))
                .filter(p -> includeRevoked || "ACTIVE".equals(p.consentStatus()))
                .toList();
        }

        @Override
        public List<SpeakerProfile> findByIds(String tenantId, List<String> profileIds) {
            return store.values().stream()
                .filter(p -> tenantId.equals(p.tenantId()))
                .filter(p -> profileIds.contains(p.id()))
                .toList();
        }

        @Override
        public List<SpeakerProfile> findByPersonIds(String tenantId, List<String> personIds) {
            return store.values().stream()
                .filter(p -> tenantId.equals(p.tenantId()))
                .filter(p -> personIds.contains(p.personId()))
                .filter(SpeakerProfile::isActive)
                .toList();
        }

        @Override
        public void updateConsentStatus(String tenantId, String profileId, String consentStatus,
                                         OffsetDateTime revokedAt, OffsetDateTime deletedAt, OffsetDateTime updatedAt) {
            consentUpdateCalls++;
            var existing = store.get(profileId);
            if (existing == null) return;
            store.put(profileId, SpeakerProfile.restore(
                existing.id(), existing.tenantId(), existing.personId(), existing.displayNameSnapshot(),
                consentStatus, existing.consentSource(), existing.consentVersion(), existing.enrolledBy(),
                revokedAt, deletedAt, existing.createdAt(), updatedAt
            ));
        }
    }

    private static final class InMemoryEnrollmentRepo implements SpeakerEnrollmentRepository {
        private final List<SpeakerEnrollmentRecord> saved = new ArrayList<>();

        @Override
        public String save(SpeakerEnrollmentRecord record) {
            saved.add(record);
            return record.id();
        }

        @Override
        public Optional<SpeakerEnrollmentRecord> findById(String tenantId, String enrollmentId) {
            return saved.stream().filter(r -> r.id().equals(enrollmentId)).findFirst();
        }

        @Override
        public List<SpeakerEnrollmentRecord> findByProfile(String tenantId, String profileId) {
            return saved.stream().filter(r -> r.speakerProfileId().equals(profileId)).toList();
        }

        @Override
        public void updateStatus(String tenantId, String enrollmentId, String enrollmentStatus,
                                  Double qualityScore, String modelVersion, String errorCode, OffsetDateTime now) {
        }
    }

    private static final class FailingProcessingTaskService extends ProcessingTaskApplicationService {
        FailingProcessingTaskService() {
            super(null, null, null, TenantScopedTransaction.immediate());
        }

        @Override
        public ProcessingTaskDTO createForSpeakerEnrollment(
            String tenantId,
            String speakerProfileId,
            String speakerEnrollmentId,
            String audioFileId,
            String audioUri,
            String language,
            String requestedBy,
            String traceId
        ) {
            throw new IllegalStateException("simulated task creation failure");
        }
    }

    private static final class SingleMeetingFileRepository implements MeetingFileRepository {
        @Override
        public MeetingFile save(MeetingFile file) {
            return file;
        }

        @Override
        public Optional<MeetingFile> findById(String tenantId, String fileId) {
            if (!"tenant_01".equals(tenantId) || !"file_01".equals(fileId)) {
                return Optional.empty();
            }
            return Optional.of(new MeetingFile(
                "file_01",
                "tenant_01",
                null,
                "AUDIO",
                "SPEAKER_ENROLLMENT",
                "voice.wav",
                "audio/wav",
                "meeting-audio",
                "tenant_01/file_01.wav",
                "tos://meeting-audio/tenant_01/file_01.wav",
                4096L,
                "sha256",
                1500L,
                "COMPLETED",
                "user_01",
                NOW,
                NOW
            ));
        }
    }

    private static final class NoOpEmbeddingRepo implements SpeakerEmbeddingRepository {
        @Override public void save(SpeakerEmbeddingRecord record) { }
        @Override public List<SpeakerEmbeddingRecord> findByProfile(String tenantId, String speakerProfileId) { return List.of(); }
        @Override public List<SpeakerEmbeddingRecord> findByProfileIds(String tenantId, java.util.Collection<String> speakerProfileIds) { return List.of(); }
        @Override public int revokeForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) { return 0; }
        @Override public int deleteForProfile(String tenantId, String speakerProfileId, OffsetDateTime now) { return 0; }
    }

    private static final class NoOpMeetingSpeakerRepo implements MeetingSpeakerRepository {
        @Override public Optional<MeetingSpeakerRecord> find(String tenantId, String meetingId, String speakerLabel) { return Optional.empty(); }
        @Override public List<MeetingSpeakerRecord> findByMeeting(String tenantId, String meetingId) { return List.of(); }
        @Override public List<String> findMeetingIdsByConfirmedPerson(String tenantId, String personId) { return List.of(); }
        @Override public void saveCandidates(String tenantId, String meetingId, String speakerLabel, List<String> candidatePersonIds, Double autoMatchScore, String matchSource, OffsetDateTime now) { }
        @Override public void confirm(String tenantId, String meetingId, String speakerLabel, String confirmedPersonId, String confirmedSpeakerProfileId, String confirmedBy, OffsetDateTime now) { }
        @Override public void reject(String tenantId, String meetingId, String speakerLabel, String rejectedBy, OffsetDateTime now) { }
    }

    private static final class NoOpKnowledgeChunkRepo implements KnowledgeChunkRepository {
        @Override public int markStaleForMeeting(String tenantId, String meetingId) { return 0; }
    }
}
