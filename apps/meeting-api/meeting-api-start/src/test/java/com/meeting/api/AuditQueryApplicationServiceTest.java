package com.meeting.api;

import com.meeting.api.app.audit.AuditQueryApplicationService;
import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.client.audit.AuditEventDTO;
import com.meeting.api.client.audit.AuditQueryFacade.AuditQueryRequest;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.client.enums.AuditResult;
import com.meeting.api.domain.audit.AuditEventReadRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditQueryApplicationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T06:00:00Z");

    private RecordingRepo repo;
    private AuditQueryApplicationService service;

    @BeforeEach
    void setUp() {
        repo = new RecordingRepo();
        service = new AuditQueryApplicationService(
            repo,
            com.meeting.api.app.common.TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
            Duration.ofDays(90)
        );
    }

    @Test
    void defaultsToLast90DaysWhenNoWindowProvided() {
        service.query(req(null, null));
        assertThat(repo.lastQuery).isNotNull();
        assertThat(repo.lastQuery.from()).isEqualTo(NOW.minusDays(90));
        assertThat(repo.lastQuery.to()).isEqualTo(NOW);
    }

    @Test
    void rejectsWindowWiderThan90Days() {
        OffsetDateTime from = NOW.minusDays(120);
        OffsetDateTime to = NOW;
        assertThatThrownBy(() -> service.query(req(from, to)))
            .isInstanceOf(ApplicationException.class)
            .matches(ex -> ((ApplicationException) ex).errorCode() == ErrorCode.AUDIT_QUERY_TOO_BROAD)
            .matches(ex -> ((ApplicationException) ex).httpStatus() == 400);
    }

    @Test
    void rejectsToBeforeFrom() {
        OffsetDateTime from = NOW;
        OffsetDateTime to = NOW.minusDays(1);
        assertThatThrownBy(() -> service.query(req(from, to)))
            .isInstanceOf(ApplicationException.class)
            .matches(ex -> ((ApplicationException) ex).errorCode() == ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void passesFiltersThroughToRepository() {
        AuditQueryRequest q = new AuditQueryRequest(
            "tenant_01", "user_42", "MEETING", "mtg_01",
            AuditAction.DELETE, AuditResult.BLOCKED,
            NOW.minusDays(7), NOW, "abc|x", 10
        );
        service.query(q);
        assertThat(repo.lastQuery.actorUserId()).isEqualTo("user_42");
        assertThat(repo.lastQuery.resourceType()).isEqualTo("MEETING");
        assertThat(repo.lastQuery.action()).isEqualTo(AuditAction.DELETE);
        assertThat(repo.lastQuery.result()).isEqualTo(AuditResult.BLOCKED);
        assertThat(repo.lastQuery.cursor()).isEqualTo("abc|x");
        assertThat(repo.lastQuery.limit()).isEqualTo(10);
    }

    @Test
    void limitClampedTo200() {
        AuditQueryRequest q = new AuditQueryRequest(
            "tenant_01", null, null, null, null, null,
            null, null, null, /* limit */ 9999
        );
        service.query(q);
        assertThat(repo.lastQuery.limit()).isEqualTo(200);
    }

    @Test
    void mapsRowToDtoPreservingPayload() {
        repo.rows.add(new AuditEventReadRepository.AuditEventRow(
            "audit_01", "tenant_01", "user_admin", "USER",
            AuditAction.LEGAL_HOLD_PLACE, "LEGAL_HOLD", "lh_01",
            AuditResult.SUCCESS, null, "trace_01",
            java.util.Map.of("scopeType", "MEETING"),
            NOW.minusHours(1)
        ));
        PageResult<AuditEventDTO> page = service.query(req(null, null));
        assertThat(page.items()).hasSize(1);
        AuditEventDTO dto = page.items().get(0);
        assertThat(dto.auditEventId()).isEqualTo("audit_01");
        assertThat(dto.action()).isEqualTo(AuditAction.LEGAL_HOLD_PLACE);
        assertThat(dto.payload()).containsEntry("scopeType", "MEETING");
    }

    private static AuditQueryRequest req(OffsetDateTime from, OffsetDateTime to) {
        return new AuditQueryRequest(
            "tenant_01", null, null, null, null, null, from, to, null, 50
        );
    }

    private static class RecordingRepo implements AuditEventReadRepository {
        AuditQuery lastQuery;
        final List<AuditEventRow> rows = new ArrayList<>();

        @Override
        public PageResult<AuditEventRow> list(AuditQuery query) {
            this.lastQuery = query;
            return new PageResult<>(rows, new PageResult.PageInfo(null, false, query.limit()));
        }
    }
}
