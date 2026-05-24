package com.meeting.api.app.breakglass;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.breakglass.BreakGlassFacade;
import com.meeting.api.client.breakglass.BreakGlassRequestDTO;
import com.meeting.api.client.breakglass.CreateBreakGlassCommand;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.BreakGlassStatus;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.audit.AuditEventLogger.AuditEntry;
import com.meeting.api.domain.breakglass.BreakGlassRequest;
import com.meeting.api.domain.breakglass.BreakGlassRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Application service for break-glass requests (Phase 7.4).
 *
 * <p>Owns the create / approve / reject / list flows. Audit rows are
 * written on every state-change path (including the
 * BREAK_GLASS_SELF_APPROVAL_FORBIDDEN rejection) — this is critical
 * because emergency-access requests are by design rare and the audit
 * trail is the only record. The expiry scanner that drives APPROVED →
 * EXPIRED lives in a separate scheduled bean ({@code BreakGlassExpiryScanner}).
 */
@Service
public class BreakGlassApplicationService implements BreakGlassFacade {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassApplicationService.class);

    private final TenantScopedTransaction tenantTx;
    private final BreakGlassRequestRepository repo;
    private final AuditEventLogger auditLogger;
    private final Clock clock;
    private final Duration approvalWindow;

    public BreakGlassApplicationService(
        TenantScopedTransaction tenantTx,
        BreakGlassRequestRepository repo,
        AuditEventLogger auditLogger,
        @Value("${meeting.break-glass.default-window-hours:4}") long windowHours
    ) {
        this(tenantTx, repo, auditLogger, Clock.systemUTC(), Duration.ofHours(windowHours));
    }

    public BreakGlassApplicationService(
        TenantScopedTransaction tenantTx,
        BreakGlassRequestRepository repo,
        AuditEventLogger auditLogger,
        Clock clock,
        Duration approvalWindow
    ) {
        this.tenantTx = tenantTx;
        this.repo = repo;
        this.auditLogger = auditLogger;
        this.clock = clock;
        this.approvalWindow = approvalWindow;
    }

    @Override
    public BreakGlassRequestDTO create(CreateBreakGlassCommand cmd) {
        return tenantTx.execute(cmd.tenantId(), cmd.requesterId(), cmd.requestId(), () -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            String id = "bg_" + UUID.randomUUID().toString().replace("-", "");
            BreakGlassRequest req = BreakGlassRequest.builder()
                .id(id)
                .tenantId(cmd.tenantId())
                .requesterId(cmd.requesterId())
                .scopeType(cmd.scopeType())
                .scopeId(cmd.scopeId())
                .reason(cmd.reason())
                .createdAt(now)
                .build();
            repo.save(req);
            auditLogger.log(AuditEntry.success(
                cmd.tenantId(), cmd.requesterId(),
                AuditAction.BREAK_GLASS_REQUEST,
                "BREAK_GLASS", id,
                Map.of(
                    "scopeType", cmd.scopeType(),
                    "scopeId", cmd.scopeId(),
                    "reason", cmd.reason()
                ),
                cmd.traceId()
            ));
            log.info(
                "break_glass_requested tenant={} request={} scope={}:{} by={}",
                cmd.tenantId(), id, cmd.scopeType(), cmd.scopeId(), cmd.requesterId()
            );
            return toDto(req);
        });
    }

    @Override
    public Optional<BreakGlassRequestDTO> get(String tenantId, String requestId) {
        return tenantTx.execute(tenantId, null, null,
            () -> repo.findById(tenantId, requestId).map(BreakGlassApplicationService::toDto));
    }

    @Override
    public PageResult<BreakGlassRequestDTO> list(
        String tenantId, BreakGlassStatus status, String cursor, int limit
    ) {
        return tenantTx.execute(tenantId, null, null, () -> {
            PageResult<BreakGlassRequest> page = repo.listByTenant(tenantId, status, cursor, limit);
            return new PageResult<>(
                page.items().stream().map(BreakGlassApplicationService::toDto).toList(),
                page.page()
            );
        });
    }

    @Override
    public BreakGlassRequestDTO approve(String tenantId, String requestId, String approverId) {
        return tenantTx.execute(tenantId, approverId, null, () -> {
            BreakGlassRequest req = loadOrThrow(tenantId, requestId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            try {
                req.approve(approverId, now, approvalWindow);
            } catch (BreakGlassRequest.SelfApprovalForbiddenException ex) {
                // Audit the rejection — important for forensics.
                auditLogger.log(AuditEntry.blocked(
                    tenantId, approverId,
                    AuditAction.BREAK_GLASS_APPROVE,
                    "BREAK_GLASS", requestId,
                    "self approval forbidden", null
                ));
                throw new ApplicationException(
                    ErrorCode.BREAK_GLASS_SELF_APPROVAL_FORBIDDEN, 403,
                    ex.getMessage(), false
                );
            } catch (IllegalStateException ex) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 409,
                    ex.getMessage(), false
                );
            }
            repo.update(req);
            auditLogger.log(AuditEntry.success(
                tenantId, approverId,
                AuditAction.BREAK_GLASS_APPROVE,
                "BREAK_GLASS", requestId,
                Map.of(
                    "validUntil", req.validUntil() == null ? "" : req.validUntil().toString(),
                    "scopeType", req.scopeType(),
                    "scopeId", req.scopeId()
                ),
                null
            ));
            log.info(
                "break_glass_approved tenant={} request={} by={} validUntil={}",
                tenantId, requestId, approverId, req.validUntil()
            );
            return toDto(req);
        });
    }

    @Override
    public BreakGlassRequestDTO reject(
        String tenantId, String requestId, String approverId, String reason
    ) {
        return tenantTx.execute(tenantId, approverId, null, () -> {
            BreakGlassRequest req = loadOrThrow(tenantId, requestId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            try {
                req.reject(approverId, reason, now);
            } catch (IllegalStateException ex) {
                throw new ApplicationException(
                    ErrorCode.VALIDATION_FAILED, 409, ex.getMessage(), false
                );
            }
            repo.update(req);
            auditLogger.log(AuditEntry.success(
                tenantId, approverId,
                AuditAction.BREAK_GLASS_REJECT,
                "BREAK_GLASS", requestId,
                Map.of("rejectReason", reason),
                null
            ));
            log.info(
                "break_glass_rejected tenant={} request={} by={} reason={}",
                tenantId, requestId, approverId, reason
            );
            return toDto(req);
        });
    }

    private BreakGlassRequest loadOrThrow(String tenantId, String requestId) {
        return repo.findById(tenantId, requestId).orElseThrow(() -> new ApplicationException(
            ErrorCode.VALIDATION_FAILED, 404,
            "break-glass request not found: " + requestId, false
        ));
    }

    private static BreakGlassRequestDTO toDto(BreakGlassRequest req) {
        return new BreakGlassRequestDTO(
            req.id(),
            req.requesterId(),
            req.scopeType(),
            req.scopeId(),
            req.reason(),
            req.status(),
            req.validFrom(),
            req.validUntil(),
            req.approverId(),
            req.approvedAt(),
            req.rejectedAt(),
            req.rejectReason(),
            req.revokedAt(),
            req.revokedBy(),
            req.createdAt()
        );
    }
}
