package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.compliance.DeletionJobRunner;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.client.enums.DeletionScopeType;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.compliance.DeletionCertificateHasher;
import com.meeting.api.domain.compliance.DeletionCertificateRepository;
import com.meeting.api.domain.compliance.DeletionCertificateRepository.DeletionCertificateRecord;
import com.meeting.api.domain.compliance.DeletionExecutorPort;
import com.meeting.api.domain.compliance.DeletionExecutorPort.DeletionOutcome;
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

class DeletionJobRunnerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T10:00:00Z");

    private InMemoryRepo repo;
    private ToggleableLegalHold legalHold;
    private CapturingExecutor executor;
    private RecordingAudit audit;
    private DeterministicHasher hasher;
    private RecordingCertificateRepo certificateRepo;
    private DeletionJobRunner runner;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRepo();
        legalHold = new ToggleableLegalHold();
        executor = new CapturingExecutor();
        audit = new RecordingAudit();
        hasher = new DeterministicHasher();
        certificateRepo = new RecordingCertificateRepo();
        runner = new DeletionJobRunner(
            repo, legalHold,
            (scope) -> scope == DeletionScopeType.MEETING ? Optional.of(executor) : Optional.empty(),
            hasher,
            certificateRepo,
            TenantScopedTransaction.immediate(),
            audit,
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
            10
        );
    }

    @Test
    void runsSucceededWhenExecutorReturnsNoFailures() {
        DeletionJob job = requestedJob("dj_ok", DeletionScopeType.MEETING, "mtg_01");
        repo.rows.put(job.id(), job);
        executor.outcome = new DeletionOutcome(
            Map.of("meetings", 1), Map.of(), Map.of(), List.of()
        );

        DeletionJobRunner.RunReport report = runner.runOnce(List.of("tenant_01"));

        assertThat(report.claimed()).isEqualTo(1);
        assertThat(report.succeeded()).isEqualTo(1);
        DeletionJob updated = repo.rows.get(job.id());
        assertThat(updated.status()).isEqualTo(DeletionJobStatus.SUCCEEDED);
        assertThat(updated.certificateHash()).isNotBlank();
        assertThat(audit.entries).hasSize(1);
    }

    @Test
    void transitionsPartialFailedWhenExecutorReportsFailures() {
        DeletionJob job = requestedJob("dj_partial", DeletionScopeType.MEETING, "mtg_02");
        repo.rows.put(job.id(), job);
        executor.outcome = new DeletionOutcome(
            Map.of("meetings", 1), Map.of(), Map.of(), List.of("file:audio:not_found")
        );

        DeletionJobRunner.RunReport report = runner.runOnce(List.of("tenant_01"));

        assertThat(report.partialFailed()).isEqualTo(1);
        assertThat(repo.rows.get(job.id()).status()).isEqualTo(DeletionJobStatus.PARTIAL_FAILED);
    }

    @Test
    void transitionsBlockedByLegalHoldOnRaceCondition() {
        DeletionJob job = requestedJob("dj_lh", DeletionScopeType.MEETING, "mtg_protected");
        repo.rows.put(job.id(), job);
        // hold placed between create and runner pickup
        legalHold.protectedKeys.add("MEETING:mtg_protected");

        DeletionJobRunner.RunReport report = runner.runOnce(List.of("tenant_01"));

        assertThat(report.blockedByLegalHold()).isEqualTo(1);
        DeletionJob updated = repo.rows.get(job.id());
        assertThat(updated.status()).isEqualTo(DeletionJobStatus.BLOCKED_BY_LEGAL_HOLD);
        assertThat(updated.errorCode()).isEqualTo(ErrorCode.DELETION_JOB_BLOCKED_BY_LEGAL_HOLD);
        assertThat(executor.calls).isEmpty();    // executor never runs
    }

    @Test
    void failsWhenNoExecutorRegisteredForScope() {
        DeletionJob job = requestedJob("dj_user", DeletionScopeType.USER, "u_42");
        repo.rows.put(job.id(), job);

        DeletionJobRunner.RunReport report = runner.runOnce(List.of("tenant_01"));

        assertThat(report.failed()).isEqualTo(1);
        assertThat(repo.rows.get(job.id()).status()).isEqualTo(DeletionJobStatus.FAILED);
    }

    @Test
    void executorThrowingTransitionsFailedAndKeepsAuditTrail() {
        DeletionJob job = requestedJob("dj_crash", DeletionScopeType.MEETING, "mtg_crash");
        repo.rows.put(job.id(), job);
        executor.throwOnExecute = new RuntimeException("simulated KMS outage");

        DeletionJobRunner.RunReport report = runner.runOnce(List.of("tenant_01"));

        assertThat(report.failed()).isEqualTo(1);
        DeletionJob updated = repo.rows.get(job.id());
        assertThat(updated.status()).isEqualTo(DeletionJobStatus.FAILED);
        assertThat(updated.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        // One BLOCKED audit row for forensic purposes.
        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0).reason()).contains("simulated KMS outage");
    }

    @Test
    void emptyTenantListYieldsZero() {
        DeletionJobRunner.RunReport report = runner.runOnce(List.of());
        assertThat(report.claimed()).isZero();
    }

    private DeletionJob requestedJob(String id, DeletionScopeType scopeType, String scopeId) {
        return DeletionJob.builder()
            .id(id)
            .tenantId("tenant_01")
            .scopeType(scopeType)
            .scopeId(scopeId)
            .requestedBy("user_compliance")
            .createdAt(NOW.minusMinutes(5))
            .build();
    }

    private static class InMemoryRepo implements DeletionJobRepository {
        final Map<String, DeletionJob> rows = new HashMap<>();

        @Override public void save(DeletionJob job) { rows.put(job.id(), job); }
        @Override public void update(DeletionJob job) { rows.put(job.id(), job); }

        @Override
        public Optional<DeletionJob> findById(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public PageResult<DeletionJob> listByTenant(String tenantId, String cursor, int limit) {
            return new PageResult<>(new ArrayList<>(rows.values()),
                new PageResult.PageInfo(null, false, limit));
        }

        @Override
        public List<DeletionJob> claimByStatus(String tenantId, DeletionJobStatus status, int limit) {
            return rows.values().stream()
                .filter(j -> tenantId.equals(j.tenantId()) && j.status() == status)
                .limit(limit)
                .toList();
        }
    }

    private static class ToggleableLegalHold implements LegalHoldCheckPort {
        final Set<String> protectedKeys = new HashSet<>();
        @Override
        public boolean isProtected(String tenantId, String scopeType, String scopeId) {
            return protectedKeys.contains(scopeType + ":" + scopeId);
        }
    }

    private static class CapturingExecutor implements DeletionExecutorPort {
        final List<String> calls = new ArrayList<>();
        DeletionOutcome outcome = new DeletionOutcome(Map.of(), Map.of(), Map.of(), List.of());
        RuntimeException throwOnExecute;

        @Override public DeletionScopeType supportedScope() { return DeletionScopeType.MEETING; }

        @Override
        public DeletionOutcome execute(String tenantId, String scopeId, String executorId) {
            calls.add(tenantId + ":" + scopeId);
            if (throwOnExecute != null) throw throwOnExecute;
            return outcome;
        }
    }

    private static class RecordingAudit implements AuditEventLogger {
        final List<AuditEntry> entries = new ArrayList<>();
        @Override public void log(AuditEntry entry) { entries.add(entry); }
    }

    private static class DeterministicHasher implements DeletionCertificateHasher {
        @Override
        public String compute(String tenantId, String jobId, DeletionOutcome outcome) {
            return "sha256:" + tenantId + ":" + jobId + ":" + outcome.deletedRows().toString();
        }
    }

    private static class RecordingCertificateRepo implements DeletionCertificateRepository {
        final List<DeletionCertificateRecord> saved = new ArrayList<>();

        @Override public void save(DeletionCertificateRecord record) { saved.add(record); }
        @Override
        public Optional<DeletionCertificateRecord> findByJobId(String tenantId, String jobId) {
            return saved.stream().filter(r -> jobId.equals(r.deletionJobId())).findFirst();
        }
    }
}
