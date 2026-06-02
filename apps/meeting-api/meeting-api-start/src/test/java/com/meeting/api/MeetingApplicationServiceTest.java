package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.meeting.MeetingApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import com.meeting.api.client.enums.MeetingStatus;
import com.meeting.api.client.enums.SecurityLevel;
import com.meeting.api.client.meeting.CreateMeetingCommand;
import com.meeting.api.client.meeting.DeleteMeetingCommand;
import com.meeting.api.client.meeting.DeleteMeetingResult;
import com.meeting.api.client.meeting.MeetingDTO;
import com.meeting.api.client.meeting.UpdateMeetingCommand;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import com.meeting.api.domain.meeting.Meeting;
import com.meeting.api.domain.meeting.MeetingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingApplicationServiceTest {

    @Test
    void createPersistsMeetingWithDefaultsAndReturnsDto() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        MeetingApplicationService service = newService(repository);

        MeetingDTO dto = service.create(new CreateMeetingCommand(
            "tenant_01",
            "Sprint Review",
            OffsetDateTime.parse("2026-01-01T10:00:00Z"),
            null,
            null,
            List.of(new CreateMeetingCommand.ParticipantCommand("person_01", "Ada", "HOST")),
            "user_01"
        ));

        assertThat(repository.saved).hasSize(1);
        Meeting saved = repository.saved.get(0);
        assertThat(saved.id()).startsWith("m_");
        assertThat(saved.tenantId()).isEqualTo("tenant_01");
        assertThat(saved.title()).isEqualTo("Sprint Review");
        assertThat(saved.status()).isEqualTo(MeetingStatus.CREATED);
        assertThat(saved.securityLevel()).isEqualTo(SecurityLevel.INTERNAL);
        assertThat(saved.language()).isEqualTo("zh");
        assertThat(saved.participants()).hasSize(1);

        assertThat(dto.meetingId()).isEqualTo(saved.id());
        assertThat(dto.tenantId()).isEqualTo(saved.tenantId());
        assertThat(dto.transcriptVersion()).isZero();
        assertThat(dto.minutesVersion()).isZero();
        assertThat(dto.participants())
            .extracting(MeetingDTO.ParticipantDTO::personId)
            .containsExactly("person_01");
    }

    @Test
    void getAndListAreTenantScopedThroughRepositoryPort() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        MeetingApplicationService service = newService(repository);
        Meeting meeting = Meeting.create(
            "m_01",
            "tenant_01",
            "Planning",
            SecurityLevel.CONFIDENTIAL,
            "en",
            List.of(),
            "user_01"
        );
        repository.save(meeting);

        assertThat(service.get("tenant_01", "m_01")).isPresent();
        assertThat(service.get("tenant_02", "m_01")).isEmpty();
        assertThat(service.list("tenant_01")).extracting(MeetingDTO::meetingId).containsExactly("m_01");
        assertThat(service.list("tenant_02")).isEmpty();
    }

    @Test
    void updateReplacesParticipantsAndReturnsLatestDto() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        repository.save(new Meeting.Builder()
            .id("m_01").tenantId("tenant_01").title("Planning")
            .securityLevel(SecurityLevel.INTERNAL).status(MeetingStatus.CREATED)
            .language("zh").transcriptVersion(3).minutesVersion(1)
            .createdAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
            .createdBy("user_01")
            .participants(List.of(new Meeting.Participant("p_old", "旧参会人", "PARTICIPANT")))
            .build());
        MeetingApplicationService service = newService(repository);

        MeetingDTO dto = service.update(new UpdateMeetingCommand(
            "tenant_01",
            "m_01",
            "Updated Planning",
            List.of(
                new CreateMeetingCommand.ParticipantCommand("p_01", "李四", "PARTICIPANT"),
                new CreateMeetingCommand.ParticipantCommand("p_02", "王五", "OBSERVER")
            ),
            3,
            "user_01",
            "req_01"
        ));

        assertThat(dto.title()).isEqualTo("Updated Planning");
        assertThat(dto.participants())
            .extracting(
                MeetingDTO.ParticipantDTO::personId,
                MeetingDTO.ParticipantDTO::displayName,
                MeetingDTO.ParticipantDTO::role
            )
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("p_01", "李四", "PARTICIPANT"),
                org.assertj.core.groups.Tuple.tuple("p_02", "王五", "OBSERVER")
            );
        Meeting saved = repository.findById("tenant_01", "m_01").orElseThrow();
        assertThat(saved.participants())
            .extracting(Meeting.Participant::personId)
            .containsExactly("p_01", "p_02");
    }

    @Test
    void updateRejectsStaleExpectedVersion() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        repository.save(new Meeting.Builder()
            .id("m_01").tenantId("tenant_01").title("Planning")
            .securityLevel(SecurityLevel.INTERNAL).status(MeetingStatus.CREATED)
            .language("zh").transcriptVersion(3).minutesVersion(1)
            .createdAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
            .createdBy("user_01").participants(List.of())
            .build());
        MeetingApplicationService service = newService(repository);

        assertThatThrownBy(() -> service.update(new UpdateMeetingCommand(
            "tenant_01",
            "m_01",
            null,
            List.of(new CreateMeetingCommand.ParticipantCommand("p_01", "李四", "PARTICIPANT")),
            2,
            "user_01",
            "req_01"
        )))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException ae = (ApplicationException) ex;
                assertThat(ae.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                assertThat(ae.httpStatus()).isEqualTo(409);
            });
        assertThat(repository.findById("tenant_01", "m_01").orElseThrow().participants()).isEmpty();
    }

    @Test
    void updateRejectsDuplicateParticipants() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        repository.save(new Meeting.Builder()
            .id("m_01").tenantId("tenant_01").title("Planning")
            .securityLevel(SecurityLevel.INTERNAL).status(MeetingStatus.CREATED)
            .language("zh").transcriptVersion(3).minutesVersion(1)
            .createdAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
            .createdBy("user_01").participants(List.of())
            .build());
        MeetingApplicationService service = newService(repository);

        assertThatThrownBy(() -> service.update(new UpdateMeetingCommand(
            "tenant_01",
            "m_01",
            null,
            List.of(
                new CreateMeetingCommand.ParticipantCommand("p_01", "李四", "PARTICIPANT"),
                new CreateMeetingCommand.ParticipantCommand("p_01", "李四重复", "PARTICIPANT")
            ),
            3,
            "user_01",
            "req_01"
        )))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException ae = (ApplicationException) ex;
                assertThat(ae.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                assertThat(ae.httpStatus()).isEqualTo(422);
            });
    }

    @Test
    void updateFailsWhenMeetingNotFound() {
        MeetingApplicationService service = newService(new CapturingMeetingRepository());

        assertThatThrownBy(() -> service.update(new UpdateMeetingCommand(
            "tenant_01",
            "m_missing",
            null,
            List.of(new CreateMeetingCommand.ParticipantCommand("p_01", "李四", "PARTICIPANT")),
            0,
            "user_01",
            "req_01"
        )))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException ae = (ApplicationException) ex;
                assertThat(ae.errorCode()).isEqualTo(ErrorCode.MEETING_NOT_FOUND);
                assertThat(ae.httpStatus()).isEqualTo(404);
            });
    }

    @Test
    void deleteSoftDeletesAndAuditsSuccess() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        repository.save(Meeting.create(
            "m_01", "tenant_01", "Planning", SecurityLevel.INTERNAL, "zh",
            List.of(), "user_01"
        ));
        StubLegalHold legalHold = new StubLegalHold(Set.of());
        CapturingAudit audit = new CapturingAudit();
        MeetingApplicationService service = new MeetingApplicationService(
            repository,
            null,
            TenantScopedTransaction.immediate(),
            legalHold,
            audit,
            fixedClock()
        );

        DeleteMeetingResult result = service.delete(new DeleteMeetingCommand(
            "tenant_01", "m_01", "user_01", "req_99",
            "user_request", null, false
        ));

        assertThat(result.meetingId()).isEqualTo("m_01");
        assertThat(result.status()).isEqualTo(MeetingStatus.DELETED);
        assertThat(repository.markDeletedCalls).containsExactly("tenant_01:m_01");
        assertThat(audit.entries).hasSize(1);
        AuditEventLogger.AuditEntry entry = audit.entries.get(0);
        assertThat(entry.action()).isEqualTo(AuditAction.DELETE);
        assertThat(entry.resourceType()).isEqualTo("MEETING");
        assertThat(entry.resourceId()).isEqualTo("m_01");
        assertThat(entry.result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(entry.payload()).containsEntry("status", "DELETED")
            .containsEntry("reason", "user_request");
    }

    @Test
    void deleteFailsWhenMeetingNotFound() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        CapturingAudit audit = new CapturingAudit();
        MeetingApplicationService service = new MeetingApplicationService(
            repository, null, TenantScopedTransaction.immediate(),
            new StubLegalHold(Set.of()), audit, fixedClock()
        );

        assertThatThrownBy(() -> service.delete(new DeleteMeetingCommand(
            "tenant_01", "m_missing", "user_01", "req_99",
            null, null, false
        )))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException ae = (ApplicationException) ex;
                assertThat(ae.errorCode()).isEqualTo(ErrorCode.MEETING_NOT_FOUND);
                assertThat(ae.httpStatus()).isEqualTo(404);
            });
        assertThat(audit.entries).isEmpty();
    }

    @Test
    void deleteBlockedByLegalHoldAuditsBlockedEntry() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        repository.save(Meeting.create(
            "m_01", "tenant_01", "Sensitive", SecurityLevel.CONFIDENTIAL, "zh",
            List.of(), "user_01"
        ));
        CapturingAudit audit = new CapturingAudit();
        MeetingApplicationService service = new MeetingApplicationService(
            repository, null, TenantScopedTransaction.immediate(),
            new StubLegalHold(Set.of("tenant_01:MEETING:m_01")),
            audit, fixedClock()
        );

        assertThatThrownBy(() -> service.delete(new DeleteMeetingCommand(
            "tenant_01", "m_01", "user_01", "req_99",
            "investigation", null, true
        )))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException ae = (ApplicationException) ex;
                assertThat(ae.errorCode()).isEqualTo(ErrorCode.LEGAL_HOLD_BLOCKED);
                assertThat(ae.httpStatus()).isEqualTo(423);
            });
        assertThat(repository.markDeletedCalls).isEmpty();
        assertThat(audit.entries).hasSize(1);
        AuditEventLogger.AuditEntry entry = audit.entries.get(0);
        assertThat(entry.result()).isEqualTo(AuditResult.BLOCKED);
        assertThat(entry.reason()).isEqualTo("legal hold");
    }

    @Test
    void deleteRejectsStaleTranscriptVersion() {
        CapturingMeetingRepository repository = new CapturingMeetingRepository();
        repository.save(new Meeting.Builder()
            .id("m_01").tenantId("tenant_01").title("Planning")
            .securityLevel(SecurityLevel.INTERNAL).status(MeetingStatus.SUCCEEDED)
            .language("zh").transcriptVersion(3).minutesVersion(1)
            .createdAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
            .createdBy("user_01").participants(List.of())
            .build());
        CapturingAudit audit = new CapturingAudit();
        MeetingApplicationService service = new MeetingApplicationService(
            repository, null, TenantScopedTransaction.immediate(),
            new StubLegalHold(Set.of()), audit, fixedClock()
        );

        assertThatThrownBy(() -> service.delete(new DeleteMeetingCommand(
            "tenant_01", "m_01", "user_01", "req_99",
            null, 1, false
        )))
            .isInstanceOf(ApplicationException.class)
            .satisfies(ex -> {
                ApplicationException ae = (ApplicationException) ex;
                assertThat(ae.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT);
                assertThat(ae.httpStatus()).isEqualTo(409);
            });
        assertThat(repository.markDeletedCalls).isEmpty();
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).reason()).isEqualTo("version mismatch");
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-05-20T10:00:00Z"), ZoneOffset.UTC);
    }

    private static MeetingApplicationService newService(MeetingRepository repository) {
        return new MeetingApplicationService(
            repository,
            null,
            TenantScopedTransaction.immediate(),
            (tenantId, scopeType, scopeId) -> false,
            entry -> { /* no-op */ },
            Clock.systemUTC()
        );
    }

    private static final class CapturingMeetingRepository implements MeetingRepository {
        private final List<Meeting> saved = new ArrayList<>();
        private final List<String> markDeletedCalls = new ArrayList<>();

        @Override
        public Meeting save(Meeting meeting) {
            saved.removeIf(existing -> existing.id().equals(meeting.id()));
            saved.add(meeting);
            return meeting;
        }

        @Override
        public Optional<Meeting> findById(String tenantId, String meetingId) {
            return saved.stream()
                .filter(meeting -> meeting.id().equals(meetingId))
                .filter(meeting -> meeting.tenantId().equals(tenantId))
                .filter(meeting -> meeting.status() != MeetingStatus.DELETED)
                .findFirst();
        }

        @Override
        public List<Meeting> findByTenantId(String tenantId) {
            return saved.stream()
                .filter(meeting -> meeting.tenantId().equals(tenantId))
                .filter(meeting -> meeting.status() != MeetingStatus.DELETED)
                .toList();
        }

        @Override
        public boolean markDeleted(String tenantId, String meetingId) {
            markDeletedCalls.add(tenantId + ":" + meetingId);
            for (int i = 0; i < saved.size(); i++) {
                Meeting m = saved.get(i);
                if (m.tenantId().equals(tenantId) && m.id().equals(meetingId)
                    && m.status() != MeetingStatus.DELETED) {
                    saved.set(i, m.markDeleted());
                    return true;
                }
            }
            return false;
        }
    }

    private static final class StubLegalHold implements LegalHoldCheckPort {
        private final Set<String> protectedKeys;

        StubLegalHold(Set<String> protectedKeys) {
            this.protectedKeys = new LinkedHashSet<>(protectedKeys);
        }

        @Override
        public boolean isProtected(String tenantId, String scopeType, String scopeId) {
            return protectedKeys.contains(tenantId + ":" + scopeType + ":" + scopeId);
        }
    }

    private static final class CapturingAudit implements AuditEventLogger {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void log(AuditEntry entry) {
            entries.add(entry);
        }
    }
}
