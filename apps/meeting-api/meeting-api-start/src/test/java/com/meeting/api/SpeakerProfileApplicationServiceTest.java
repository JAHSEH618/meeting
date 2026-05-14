package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.speaker.SpeakerProfileApplicationService;
import com.meeting.api.client.speaker.CreateSpeakerEnrollmentCommand;
import com.meeting.api.client.speaker.CreateSpeakerProfileCommand;
import com.meeting.api.domain.speaker.SpeakerEnrollmentRepository;
import com.meeting.api.domain.speaker.SpeakerProfile;
import com.meeting.api.domain.speaker.SpeakerProfileRepository;
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

        var list = service.list("tenant_01");
        assertThat(list).extracting("speakerProfileId").containsExactly(p1.speakerProfileId());
    }

    private static SpeakerProfileApplicationService service(InMemoryProfileRepo profiles, InMemoryEnrollmentRepo enrollments) {
        return new SpeakerProfileApplicationService(
            profiles, enrollments, TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    private static final class InMemoryProfileRepo implements SpeakerProfileRepository {
        private final Map<String, SpeakerProfile> store = new LinkedHashMap<>();

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
        public void updateConsentStatus(String tenantId, String profileId, String consentStatus,
                                         OffsetDateTime revokedAt, OffsetDateTime deletedAt, OffsetDateTime updatedAt) {
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
}
