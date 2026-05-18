package com.meeting.api.app.audit;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.audit.AuditEventDTO;
import com.meeting.api.client.audit.AuditQueryFacade;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.domain.audit.AuditEventReadRepository;
import com.meeting.api.domain.audit.AuditEventReadRepository.AuditEventRow;
import com.meeting.api.domain.audit.AuditEventReadRepository.AuditQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Filters audit-event reads behind the OpenAPI query endpoint. Phase 7.5
 * caps the {@code (from, to)} window at 90 days — anything wider returns
 * {@link ErrorCode#AUDIT_QUERY_TOO_BROAD} so backups stay tractable
 * and operator queries focus on incident windows.
 */
@Service
public class AuditQueryApplicationService implements AuditQueryFacade {

    private final AuditEventReadRepository repository;
    private final Clock clock;
    private final Duration maxWindow;

    public AuditQueryApplicationService(
        AuditEventReadRepository repository,
        @Value("${meeting.audit.query.max-window-days:90}") long maxWindowDays
    ) {
        this(repository, Clock.systemUTC(), Duration.ofDays(maxWindowDays));
    }

    public AuditQueryApplicationService(
        AuditEventReadRepository repository,
        Clock clock,
        Duration maxWindow
    ) {
        this.repository = repository;
        this.clock = clock;
        this.maxWindow = maxWindow;
    }

    @Override
    public PageResult<AuditEventDTO> query(AuditQueryRequest req) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime defaultFrom = now.minus(maxWindow);
        OffsetDateTime from = req.from() != null ? req.from() : defaultFrom;
        OffsetDateTime to = req.to() != null ? req.to() : now;

        if (to.isBefore(from)) {
            throw new ApplicationException(
                ErrorCode.VALIDATION_FAILED, 400,
                "audit query 'to' must be >= 'from'", false
            );
        }
        Duration window = Duration.between(from, to);
        if (window.compareTo(maxWindow) > 0) {
            throw new ApplicationException(
                ErrorCode.AUDIT_QUERY_TOO_BROAD, 400,
                "audit query window must be <= " + maxWindow.toDays() + " days; got "
                    + window.toDays() + " days",
                false
            );
        }

        AuditQuery domainQuery = new AuditQuery(
            req.tenantId(),
            req.actorUserId(),
            req.resourceType(),
            req.resourceId(),
            req.action(),
            req.result(),
            from,
            to,
            req.cursor(),
            req.limit()
        );
        PageResult<AuditEventRow> page = repository.list(domainQuery);
        return new PageResult<>(
            page.items().stream().map(AuditQueryApplicationService::toDto).toList(),
            page.page()
        );
    }

    private static AuditEventDTO toDto(AuditEventRow row) {
        return new AuditEventDTO(
            row.id(),
            row.actorUserId(),
            row.actorType(),
            row.action(),
            row.resourceType(),
            row.resourceId(),
            row.result(),
            row.reason(),
            row.traceId(),
            row.payload(),
            row.createdAt()
        );
    }
}
