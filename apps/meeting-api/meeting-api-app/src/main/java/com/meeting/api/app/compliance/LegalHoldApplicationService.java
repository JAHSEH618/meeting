package com.meeting.api.app.compliance;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.compliance.CreateLegalHoldCommand;
import com.meeting.api.client.compliance.LegalHoldDTO;
import com.meeting.api.client.compliance.LegalHoldFacade;
import com.meeting.api.client.enums.LegalHoldStatus;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.audit.AuditEventLogger.AuditEntry;
import com.meeting.api.domain.compliance.LegalHold;
import com.meeting.api.domain.compliance.LegalHoldRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Application service for legal holds. Owns the create / release
 * lifecycle behind {@code /api/legal-holds*}. Every entry point —
 * including {@code get} / {@code list} — wraps the call in
 * {@link TenantScopedTransaction} so {@code app.tenant_id} is set
 * before the underlying SELECT runs, keeping the row-level security
 * policies enforceable on read paths too.
 */
@Service
public class LegalHoldApplicationService implements LegalHoldFacade {

    private static final Logger log = LoggerFactory.getLogger(LegalHoldApplicationService.class);

    private final TenantScopedTransaction tenantTx;
    private final LegalHoldRepository repo;
    private final AuditEventLogger auditLogger;
    private final Clock clock;

    @Autowired
    public LegalHoldApplicationService(
        TenantScopedTransaction tenantTx,
        LegalHoldRepository repo,
        AuditEventLogger auditLogger
    ) {
        this(tenantTx, repo, auditLogger, Clock.systemUTC());
    }
    public LegalHoldApplicationService(
        TenantScopedTransaction tenantTx,
        LegalHoldRepository repo,
        AuditEventLogger auditLogger,
        Clock clock
    ) {
        this.tenantTx = tenantTx;
        this.repo = repo;
        this.auditLogger = auditLogger;
        this.clock = clock;
    }

    @Override
    public LegalHoldDTO create(CreateLegalHoldCommand cmd) {
        return tenantTx.execute(cmd.tenantId(), cmd.requestedBy(), cmd.requestId(), () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            String holdId = "lh_" + UUID.randomUUID().toString().replace("-", "");
            LegalHold hold = LegalHold.builder()
                .id(holdId)
                .tenantId(cmd.tenantId())
                .scopeType(cmd.scopeType())
                .scopeId(cmd.scopeId())
                .reason(cmd.reason())
                .requestedBy(cmd.requestedBy())
                .approvedBy(cmd.approvedBy())
                .createdAt(now)
                .build();
            repo.save(hold);
            auditLogger.log(AuditEntry.success(
                cmd.tenantId(),
                cmd.requestedBy(),
                AuditAction.LEGAL_HOLD_PLACE,
                "LEGAL_HOLD",
                holdId,
                Map.of(
                    "scopeType", cmd.scopeType().name(),
                    "scopeId", cmd.scopeId(),
                    "reason", cmd.reason()
                ),
                cmd.traceId()
            ));
            log.info(
                "legal_hold_placed tenant={} hold={} scope={}:{} by={}",
                cmd.tenantId(), holdId, cmd.scopeType(), cmd.scopeId(), cmd.requestedBy()
            );
            return toDto(hold);
        });
    }

    @Override
    public Optional<LegalHoldDTO> get(String tenantId, String legalHoldId) {
        return tenantTx.execute(tenantId, null, null,
            () -> repo.findById(tenantId, legalHoldId).map(LegalHoldApplicationService::toDto));
    }

    @Override
    public PageResult<LegalHoldDTO> list(String tenantId, String cursor, int limit) {
        return tenantTx.execute(tenantId, null, null, () -> {
            PageResult<LegalHold> page = repo.listByTenant(tenantId, cursor, limit);
            return new PageResult<>(
                page.items().stream().map(LegalHoldApplicationService::toDto).toList(),
                page.page()
            );
        });
    }

    @Override
    public void release(
        String tenantId, String legalHoldId,
        String releasedBy, String releaseReason
    ) {
        tenantTx.executeWithoutResult(tenantId, releasedBy, null, () -> {
            LegalHold hold = repo.findById(tenantId, legalHoldId)
                .orElseThrow(() -> new ApplicationException(
                    ErrorCode.LEGAL_HOLD_NOT_FOUND, 404,
                    "legal hold not found: " + legalHoldId, false
                ));
            if (hold.status() != LegalHoldStatus.ACTIVE) {
                throw new ApplicationException(
                    ErrorCode.LEGAL_HOLD_ALREADY_RELEASED, 409,
                    "legal hold already released: " + legalHoldId, false
                );
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            hold.release(releasedBy, releaseReason, now);
            repo.update(hold);
            auditLogger.log(AuditEntry.success(
                tenantId,
                releasedBy,
                AuditAction.LEGAL_HOLD_RELEASE,
                "LEGAL_HOLD",
                legalHoldId,
                Map.of(
                    "scopeType", hold.scopeType().name(),
                    "scopeId", hold.scopeId(),
                    "releaseReason", releaseReason
                ),
                null
            ));
            log.info(
                "legal_hold_released tenant={} hold={} scope={}:{} by={} reason={}",
                tenantId, legalHoldId, hold.scopeType(), hold.scopeId(),
                releasedBy, releaseReason
            );
        });
    }

    private static LegalHoldDTO toDto(LegalHold hold) {
        return new LegalHoldDTO(
            hold.id(),
            hold.scopeType(),
            hold.scopeId(),
            hold.reason(),
            hold.status(),
            hold.requestedBy(),
            hold.approvedBy(),
            hold.createdAt(),
            hold.releasedAt(),
            hold.releasedBy(),
            hold.releaseReason()
        );
    }
}
