package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.compliance.DeletionJobApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.compliance.CreateDeletionJobCommand;
import com.meeting.api.client.compliance.DeletionJobDTO;
import com.meeting.api.client.compliance.DeletionJobFacade.DeletionCertificateDTO;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.compliance.DeletionCertificateRepository;
import com.meeting.api.domain.compliance.DeletionCertificateRepository.DeletionCertificateRecord;
import com.meeting.api.domain.compliance.DeletionJob;
import com.meeting.api.domain.compliance.DeletionJobRepository;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeletionJobApplicationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T04:00:00Z");

    private InMemoryDeletionJobRepository repo;
    private ToggleableLegalHoldCheck legalHold;
    private RecordingAuditLogger audit;
    private InMemoryCertificateRepository certificateRepo;
    private DeletionJobApplicationService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryDeletionJobRepository();
        legalHold = new ToggleableLegalHoldCheck();
        audit = new RecordingAuditLogger();
        certificateRepo = new InMemoryCertificateRepository();
        service = new DeletionJobApplicationService(
            TenantScopedTransaction.immediate(),
            repo,
            legalHold,
            audit,
            certificateRepo,
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    @Test
    void createPersistsRequestedJobAndWritesAuditSuccess() {
        DeletionJobDTO dto = service.create(cmdFor(DeletionScopeType.MEETING, "mtg_01"));

        assertThat(dto.status()).isEqualTo(DeletionJobStatus.REQUESTED);
        assertThat(dto.deletionJobId()).startsWith("dj_");
        assertThat(dto.legalHoldChecked()).isTrue();
        assertThat(dto.errorCode()).isNull();
        assertThat(dto.finishedAt()).isNull();

        assertThat(repo.rows).hasSize(1);
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).action()).isEqualTo(AuditAction.DELETION_REQUEST);
        assertThat(audit.entries.get(0).result()).isEqualTo(AuditResult.SUCCESS);
    }

    @Test
    void createWithLegalHoldReturnsBlockedAndAuditsBlocked() {
        legalHold.protectedKeys.add("MEETING:mtg_protected");

        DeletionJobDTO dto = service.create(cmdFor(DeletionScopeType.MEETING, "mtg_protected"));

        assertThat(dto.status()).isEqualTo(DeletionJobStatus.BLOCKED_BY_LEGAL_HOLD);
        assertThat(dto.errorCode()).isEqualTo(ErrorCode.DELETION_JOB_BLOCKED_BY_LEGAL_HOLD.name());
        assertThat(dto.legalHoldChecked()).isTrue();
        assertThat(dto.finishedAt()).isEqualTo(NOW);

        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).result()).isEqualTo(AuditResult.BLOCKED);
        assertThat(audit.entries.get(0).reason()).contains("legal hold");
    }

    @Test
    void userScopeBypassesLegalHoldCheck() {
        // USER scope has no legal-hold mirror in phase 1 — service should
        // not call the port and create REQUESTED unconditionally.
        legalHold.protectedKeys.add("USER:u_admin");
        DeletionJobDTO dto = service.create(cmdFor(DeletionScopeType.USER, "u_admin"));
        assertThat(dto.status()).isEqualTo(DeletionJobStatus.REQUESTED);
        assertThat(legalHold.calls).isEmpty();
    }

    @Test
    void getReturnsEmptyForUnknownId() {
        assertThat(service.get("tenant_01", "dj_missing")).isEmpty();
    }

    @Test
    void listPaginatesPersistedJobs() {
        for (int i = 0; i < 3; i++) {
            service.create(cmdFor(DeletionScopeType.MEETING, "mtg_" + i));
        }
        PageResult<DeletionJobDTO> page = service.list("tenant_01", null, 10);
        assertThat(page.items()).hasSize(3);
    }

    private CreateDeletionJobCommand cmdFor(DeletionScopeType type, String scopeId) {
        return new CreateDeletionJobCommand(
            "tenant_01", type, scopeId, "regulator request",
            "user_compliance", null, "req_01", "trace_01"
        );
    }

    private static class InMemoryDeletionJobRepository implements DeletionJobRepository {
        final Map<String, DeletionJob> rows = new HashMap<>();

        @Override public void save(DeletionJob job) { rows.put(job.id(), job); }
        @Override public void update(DeletionJob job) { rows.put(job.id(), job); }

        @Override
        public Optional<DeletionJob> findById(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public PageResult<DeletionJob> listByTenant(String tenantId, String cursor, int limit) {
            return new PageResult<>(
                new ArrayList<>(rows.values()),
                new PageResult.PageInfo(null, false, limit)
            );
        }

        @Override
        public List<DeletionJob> claimByStatus(String tenantId, DeletionJobStatus status, int limit) {
            return rows.values().stream().filter(j -> j.status() == status).limit(limit).toList();
        }
    }

    private static class ToggleableLegalHoldCheck implements LegalHoldCheckPort {
        final Set<String> protectedKeys = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        @Override
        public boolean isProtected(String tenantId, String scopeType, String scopeId) {
            calls.add(scopeType + ":" + scopeId);
            return protectedKeys.contains(scopeType + ":" + scopeId);
        }
    }

    private static class RecordingAuditLogger implements AuditEventLogger {
        final List<AuditEntry> entries = new ArrayList<>();
        @Override public void log(AuditEntry entry) { entries.add(entry); }
    }

    private static class InMemoryCertificateRepository implements DeletionCertificateRepository {
        final Map<String, DeletionCertificateRecord> byJobId = new HashMap<>();

        @Override public void save(DeletionCertificateRecord record) {
            byJobId.put(record.deletionJobId(), record);
        }
        @Override
        public Optional<DeletionCertificateRecord> findByJobId(String tenantId, String jobId) {
            return Optional.ofNullable(byJobId.get(jobId));
        }
    }
}
