package com.meeting.api.app.compliance;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.client.enums.LegalHoldScopeType;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.audit.AuditEventLogger.AuditEntry;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs queued deletion jobs. Scheduled bean wraps {@link #runOnce} via
 * a separate Spring config in start; tests drive {@link #runOnce}
 * directly so they don't need the scheduler.
 *
 * <p>Per-tenant flow:
 * <ol>
 *   <li>Claim REQUESTED rows with {@code FOR UPDATE SKIP LOCKED};</li>
 *   <li>Re-check {@link LegalHoldCheckPort} (race-condition defence —
 *       a hold may have been placed between create and runner pickup).
 *       Blocked rows transition to {@code BLOCKED_BY_LEGAL_HOLD};</li>
 *   <li>Mark the row RUNNING and route to the
 *       {@link DeletionExecutorPort} for the scope type;</li>
 *   <li>On success / partial-failure, stamp deleted-rows / files /
 *       kms maps + certificate hash and transition to SUCCEEDED or
 *       PARTIAL_FAILED. Unexpected exceptions transition to FAILED.</li>
 * </ol>
 *
 * <p>Every step audits via {@link AuditEventLogger} so the security
 * trail is complete even when execution crashes.
 */
public class DeletionJobRunner {

    private static final Logger log = LoggerFactory.getLogger(DeletionJobRunner.class);

    private final DeletionJobRepository repo;
    private final LegalHoldCheckPort legalHoldCheck;
    private final Function<com.meeting.api.client.enums.DeletionScopeType, Optional<DeletionExecutorPort>> executorLookup;
    private final DeletionCertificateHasher hasher;
    private final DeletionCertificateRepository certificateRepository;
    private final TenantScopedTransaction tenantTx;
    private final AuditEventLogger audit;
    private final Clock clock;
    private final int batchSize;

    public DeletionJobRunner(
        DeletionJobRepository repo,
        LegalHoldCheckPort legalHoldCheck,
        Function<com.meeting.api.client.enums.DeletionScopeType, Optional<DeletionExecutorPort>> executorLookup,
        DeletionCertificateHasher hasher,
        DeletionCertificateRepository certificateRepository,
        TenantScopedTransaction tenantTx,
        AuditEventLogger audit,
        Clock clock,
        int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.repo = repo;
        this.legalHoldCheck = legalHoldCheck;
        this.executorLookup = executorLookup;
        this.hasher = hasher;
        this.certificateRepository = certificateRepository;
        this.tenantTx = tenantTx;
        this.audit = audit;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public RunReport runOnce(List<String> tenantIds) {
        int claimed = 0, succeeded = 0, partial = 0, failed = 0, blocked = 0;
        for (String tenantId : tenantIds) {
            RunReport perTenant = runTenant(tenantId);
            claimed += perTenant.claimed();
            succeeded += perTenant.succeeded();
            partial += perTenant.partialFailed();
            failed += perTenant.failed();
            blocked += perTenant.blockedByLegalHold();
        }
        return new RunReport(claimed, succeeded, partial, failed, blocked);
    }

    private RunReport runTenant(String tenantId) {
        List<DeletionJob> claimed = tenantTx.execute(tenantId, "deletion-runner", null, () ->
            repo.claimByStatus(tenantId, DeletionJobStatus.REQUESTED, batchSize)
        );
        if (claimed.isEmpty()) return new RunReport(0, 0, 0, 0, 0);

        int s = 0, p = 0, f = 0, b = 0;
        for (DeletionJob job : claimed) {
            DeletionJobStatus terminal = executeOne(job);
            switch (terminal) {
                case SUCCEEDED -> s++;
                case PARTIAL_FAILED -> p++;
                case BLOCKED_BY_LEGAL_HOLD -> b++;
                default -> f++;
            }
        }
        return new RunReport(claimed.size(), s, p, f, b);
    }

    private DeletionJobStatus executeOne(DeletionJob job) {
        return tenantTx.execute(job.tenantId(), "deletion-runner:" + job.id(), null, () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);

            // 2. Second LH check (race-condition defence).
            String lhScope = mapToLegalHoldScope(job.scopeType());
            if (lhScope != null && legalHoldCheck.isProtected(job.tenantId(), lhScope, job.scopeId())) {
                job.markBlockedByLegalHold(now);
                repo.update(job);
                audit.log(AuditEntry.blocked(
                    job.tenantId(), "deletion-runner",
                    AuditAction.DELETION_EXECUTE,
                    "DELETION_JOB", job.id(),
                    "blocked by legal hold at runner-time", null
                ));
                log.info(
                    "deletion_runner_blocked tenant={} job={} scope={}:{}",
                    job.tenantId(), job.id(), job.scopeType(), job.scopeId()
                );
                return DeletionJobStatus.BLOCKED_BY_LEGAL_HOLD;
            }

            // 3. Find an executor.
            Optional<DeletionExecutorPort> executor = executorLookup.apply(job.scopeType());
            if (executor.isEmpty()) {
                job.markRunning(now);
                job.markFailed(ErrorCode.INTERNAL_ERROR, now);
                repo.update(job);
                audit.log(AuditEntry.blocked(
                    job.tenantId(), "deletion-runner",
                    AuditAction.DELETION_EXECUTE,
                    "DELETION_JOB", job.id(),
                    "no executor registered for scope " + job.scopeType(), null
                ));
                log.warn(
                    "deletion_runner_no_executor tenant={} job={} scope={}",
                    job.tenantId(), job.id(), job.scopeType()
                );
                return DeletionJobStatus.FAILED;
            }

            // 4. RUNNING + execute + stamp certificate hash.
            job.markRunning(now);
            repo.update(job);
            try {
                DeletionOutcome outcome = executor.get()
                    .execute(job.tenantId(), job.scopeId(), "deletion-runner");
                String certHash = hasher.compute(job.tenantId(), job.id(), outcome);
                OffsetDateTime finishedAt = OffsetDateTime.now(clock);
                if (outcome.isFullSuccess()) {
                    job.markSucceeded(
                        outcome.deletedRows(),
                        outcome.deletedFiles(),
                        outcome.kmsKeysDestroyed(),
                        certHash,
                        finishedAt
                    );
                } else {
                    job.markPartialFailed(
                        outcome.deletedRows(),
                        outcome.deletedFiles(),
                        outcome.kmsKeysDestroyed(),
                        certHash,
                        finishedAt
                    );
                }
                repo.update(job);
                persistCertificate(job, outcome, certHash, finishedAt);
                audit.log(AuditEntry.success(
                    job.tenantId(), "deletion-runner",
                    AuditAction.DELETION_EXECUTE,
                    "DELETION_JOB", job.id(),
                    Map.of(
                        "status", job.status().name(),
                        "certificateHash", certHash,
                        "failedItems", outcome.failedItems().size()
                    ),
                    null
                ));
                log.info(
                    "deletion_runner_complete tenant={} job={} status={} failed={}",
                    job.tenantId(), job.id(), job.status(), outcome.failedItems().size()
                );
                return job.status();
            } catch (RuntimeException ex) {
                OffsetDateTime finishedAt = OffsetDateTime.now(clock);
                job.markFailed(ErrorCode.INTERNAL_ERROR, finishedAt);
                repo.update(job);
                audit.log(AuditEntry.blocked(
                    job.tenantId(), "deletion-runner",
                    AuditAction.DELETION_EXECUTE,
                    "DELETION_JOB", job.id(),
                    "executor threw: " + ex.getMessage(), null
                ));
                log.warn(
                    "deletion_runner_failed tenant={} job={} error={}",
                    job.tenantId(), job.id(), ex.toString()
                );
                return DeletionJobStatus.FAILED;
            }
        });
    }

    /**
     * Translate {@code DeletionScopeType} to the wire key used by
     * {@link LegalHoldCheckPort} (matches {@link LegalHoldScopeType}).
     * USER / TENANT have no direct legal-hold scope mirror in phase 1.
     */
    private static String mapToLegalHoldScope(com.meeting.api.client.enums.DeletionScopeType type) {
        return switch (type) {
            case MEETING -> "MEETING";
            case DOCUMENT -> "DOCUMENT";
            case SPEAKER_PROFILE -> "SPEAKER_PROFILE";
            case PROJECT -> "PROJECT";
            case USER, TENANT -> null;
        };
    }

    private void persistCertificate(
        DeletionJob job, DeletionOutcome outcome, String certHash, OffsetDateTime finishedAt
    ) {
        // Convert deletedFiles map → list-of-maps for the certificate
        // schema. Phase 1 has no file-level sha256 attached, so the
        // object_hashes_json column stays empty.
        List<Map<String, Object>> deletedFiles = outcome.deletedFiles().entrySet().stream()
            .map(e -> (Map<String, Object>) Map.<String, Object>of("key", e.getKey(), "value", e.getValue()))
            .toList();

        certificateRepository.save(new DeletionCertificateRecord(
            "cert_" + UUID.randomUUID().toString().replace("-", ""),
            job.tenantId(),
            job.id(),
            job.scopeType(),
            job.scopeId(),
            /* objectHashes */ List.of(),
            outcome.deletedRows(),
            deletedFiles,
            outcome.failedItems(),
            certHash,
            finishedAt
        ));
    }

    public record RunReport(int claimed, int succeeded, int partialFailed, int failed, int blockedByLegalHold) {}
}
