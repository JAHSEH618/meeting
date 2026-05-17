package com.meeting.api.app.compliance;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.compliance.CreateDeletionJobCommand;
import com.meeting.api.client.compliance.DeletionJobDTO;
import com.meeting.api.client.compliance.DeletionJobFacade;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.DeletionJobStatus;
import com.meeting.api.client.enums.LegalHoldScopeType;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.audit.AuditEventLogger.AuditEntry;
import com.meeting.api.domain.compliance.DeletionJob;
import com.meeting.api.domain.compliance.DeletionJobRepository;
import com.meeting.api.domain.compliance.LegalHoldCheckPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Schedules deletion jobs. Phase 7.3 scope: pre-flight legal-hold
 * check + audit log + persist REQUESTED row + return immediately
 * (202 Accepted semantics). The actual executor / certificate
 * generator lands in a follow-up runner PR.
 *
 * <p>Even though execution is deferred, this service still writes a
 * {@link AuditAction#DELETION_REQUEST} audit row in every code path
 * — including the legal-hold rejection — so the audit trail covers
 * intent even when no row was created.
 */
@Service
public class DeletionJobApplicationService implements DeletionJobFacade {

    private static final Logger log = LoggerFactory.getLogger(DeletionJobApplicationService.class);

    private final TenantScopedTransaction tenantTx;
    private final DeletionJobRepository repo;
    private final LegalHoldCheckPort legalHoldCheck;
    private final AuditEventLogger auditLogger;
    private final Clock clock;

    public DeletionJobApplicationService(
        TenantScopedTransaction tenantTx,
        DeletionJobRepository repo,
        LegalHoldCheckPort legalHoldCheck,
        AuditEventLogger auditLogger
    ) {
        this(tenantTx, repo, legalHoldCheck, auditLogger, Clock.systemUTC());
    }

    public DeletionJobApplicationService(
        TenantScopedTransaction tenantTx,
        DeletionJobRepository repo,
        LegalHoldCheckPort legalHoldCheck,
        AuditEventLogger auditLogger,
        Clock clock
    ) {
        this.tenantTx = tenantTx;
        this.repo = repo;
        this.legalHoldCheck = legalHoldCheck;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    @Override
    public DeletionJobDTO create(CreateDeletionJobCommand cmd) {
        return tenantTx.execute(cmd.tenantId(), cmd.requestedBy(), cmd.requestId(), () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            String jobId = "dj_" + UUID.randomUUID().toString().replace("-", "");

            // Pre-flight check. If a hold protects the scope, persist
            // BLOCKED_BY_LEGAL_HOLD up front so the operator can see why
            // their request didn't run. The runner runs a second check
            // when it claims a REQUESTED row (race-condition defence).
            String scopeKey = mapScopeKey(cmd.scopeType());
            boolean blocked = scopeKey != null
                && legalHoldCheck.isProtected(cmd.tenantId(), scopeKey, cmd.scopeId());

            DeletionJobStatus initialStatus = blocked
                ? DeletionJobStatus.BLOCKED_BY_LEGAL_HOLD
                : DeletionJobStatus.REQUESTED;

            DeletionJob job = DeletionJob.builder()
                .id(jobId)
                .tenantId(cmd.tenantId())
                .scopeType(cmd.scopeType())
                .scopeId(cmd.scopeId())
                .requestedBy(cmd.requestedBy())
                .approvedBy(cmd.approvedBy())
                .status(initialStatus)
                .legalHoldChecked(true)
                .createdAt(now)
                .finishedAt(blocked ? now : null)
                .errorCode(blocked
                    ? com.meeting.api.client.common.ErrorCode.DELETION_JOB_BLOCKED_BY_LEGAL_HOLD
                    : null)
                .build();
            repo.save(job);

            auditLogger.log(blocked
                ? AuditEntry.blocked(
                    cmd.tenantId(), cmd.requestedBy(),
                    AuditAction.DELETION_REQUEST,
                    "DELETION_JOB", jobId,
                    "blocked by legal hold", cmd.traceId())
                : AuditEntry.success(
                    cmd.tenantId(), cmd.requestedBy(),
                    AuditAction.DELETION_REQUEST,
                    "DELETION_JOB", jobId,
                    Map.of(
                        "scopeType", cmd.scopeType().name(),
                        "scopeId", cmd.scopeId(),
                        "reason", cmd.reason()
                    ),
                    cmd.traceId()));

            log.info(
                "deletion_job_{} tenant={} job={} scope={}:{} by={}",
                blocked ? "blocked_legal_hold" : "requested",
                cmd.tenantId(), jobId, cmd.scopeType(), cmd.scopeId(), cmd.requestedBy()
            );
            return toDto(job);
        });
    }

    @Override
    public Optional<DeletionJobDTO> get(String tenantId, String deletionJobId) {
        return repo.findById(tenantId, deletionJobId)
            .map(DeletionJobApplicationService::toDto);
    }

    @Override
    public PageResult<DeletionJobDTO> list(String tenantId, String cursor, int limit) {
        PageResult<DeletionJob> page = repo.listByTenant(tenantId, cursor, limit);
        return new PageResult<>(
            page.items().stream().map(DeletionJobApplicationService::toDto).toList(),
            page.page()
        );
    }

    /**
     * Translate {@code DeletionScopeType} to the wire key used by
     * {@link LegalHoldCheckPort} (which matches {@link LegalHoldScopeType}).
     * {@code USER}, {@code PROJECT}, {@code TENANT} have no direct
     * legal-hold scope mirror in phase 1 → return null so the call is
     * skipped (executor will surface its own errors).
     */
    private static String mapScopeKey(com.meeting.api.client.enums.DeletionScopeType type) {
        return switch (type) {
            case MEETING -> "MEETING";
            case DOCUMENT -> "DOCUMENT";
            case SPEAKER_PROFILE -> "SPEAKER_PROFILE";
            case PROJECT -> "PROJECT";
            case USER, TENANT -> null;
        };
    }

    private static DeletionJobDTO toDto(DeletionJob job) {
        return new DeletionJobDTO(
            job.id(),
            job.scopeType(),
            job.scopeId(),
            job.status(),
            job.requestedBy(),
            job.approvedBy(),
            job.legalHoldChecked(),
            job.deletedRowsJson(),
            job.deletedFilesJson(),
            job.kmsKeysDestroyedJson(),
            job.certificateHash(),
            job.errorCode() == null ? null : job.errorCode().name(),
            job.createdAt(),
            job.finishedAt()
        );
    }
}
