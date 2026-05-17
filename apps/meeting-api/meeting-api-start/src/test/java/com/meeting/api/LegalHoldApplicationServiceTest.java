package com.meeting.api;

import com.meeting.api.app.common.ApplicationException;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.compliance.LegalHoldApplicationService;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.compliance.CreateLegalHoldCommand;
import com.meeting.api.client.compliance.LegalHoldDTO;
import com.meeting.api.client.enums.LegalHoldScopeType;
import com.meeting.api.client.enums.LegalHoldStatus;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.domain.compliance.LegalHold;
import com.meeting.api.domain.compliance.LegalHoldRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalHoldApplicationServiceTest {

    private static final java.time.OffsetDateTime NOW =
        java.time.OffsetDateTime.parse("2026-05-18T03:00:00Z");

    private InMemoryLegalHoldRepository repo;
    private RecordingAuditLogger audit;
    private LegalHoldApplicationService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryLegalHoldRepository();
        audit = new RecordingAuditLogger();
        service = new LegalHoldApplicationService(
            TenantScopedTransaction.immediate(),
            repo,
            audit,
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    @Test
    void createPersistsActiveHold() {
        LegalHoldDTO dto = service.create(new CreateLegalHoldCommand(
            "tenant_01", LegalHoldScopeType.MEETING, "mtg_01",
            "regulator inquiry", "user_compliance", null,
            "req_01", "trace_01"
        ));

        assertThat(dto.legalHoldId()).startsWith("lh_");
        assertThat(dto.status()).isEqualTo(LegalHoldStatus.ACTIVE);
        assertThat(dto.scopeType()).isEqualTo(LegalHoldScopeType.MEETING);
        assertThat(dto.scopeId()).isEqualTo("mtg_01");
        assertThat(dto.createdAt()).isEqualTo(NOW);
        assertThat(repo.rows).hasSize(1);
        assertThat(repo.rows.values().iterator().next().isActive()).isTrue();
    }

    @Test
    void releaseTransitionsToReleased() {
        LegalHoldDTO created = service.create(new CreateLegalHoldCommand(
            "tenant_01", LegalHoldScopeType.MEETING, "mtg_01",
            "regulator inquiry", "user_compliance", null,
            "req_01", "trace_01"
        ));

        service.release("tenant_01", created.legalHoldId(), "user_admin", "case closed");

        LegalHold persisted = repo.rows.get(created.legalHoldId());
        assertThat(persisted.status()).isEqualTo(LegalHoldStatus.RELEASED);
        assertThat(persisted.releasedBy()).isEqualTo("user_admin");
        assertThat(persisted.releaseReason()).isEqualTo("case closed");
    }

    @Test
    void releaseUnknownReturns404() {
        assertThatThrownBy(() -> service.release(
            "tenant_01", "lh_unknown", "user_admin", "n/a"
        )).isInstanceOf(ApplicationException.class)
          .matches(ex -> ((ApplicationException) ex).errorCode() == ErrorCode.LEGAL_HOLD_NOT_FOUND)
          .matches(ex -> ((ApplicationException) ex).httpStatus() == 404);
    }

    @Test
    void releaseAlreadyReleasedReturns409() {
        LegalHoldDTO created = service.create(new CreateLegalHoldCommand(
            "tenant_01", LegalHoldScopeType.MEETING, "mtg_01",
            "x", "user_compliance", null, "req_01", "trace_01"
        ));
        service.release("tenant_01", created.legalHoldId(), "user_admin", "first");

        assertThatThrownBy(() -> service.release(
            "tenant_01", created.legalHoldId(), "user_admin", "second"
        )).isInstanceOf(ApplicationException.class)
          .matches(ex -> ((ApplicationException) ex).errorCode() == ErrorCode.LEGAL_HOLD_ALREADY_RELEASED)
          .matches(ex -> ((ApplicationException) ex).httpStatus() == 409);
    }

    @Test
    void getReturnsEmptyForUnknownId() {
        assertThat(service.get("tenant_01", "lh_unknown")).isEmpty();
    }

    @Test
    void listReturnsAllForTenant() {
        for (int i = 0; i < 3; i++) {
            service.create(new CreateLegalHoldCommand(
                "tenant_01", LegalHoldScopeType.MEETING, "mtg_" + i,
                "reason " + i, "user_compliance", null, "req", "trace"
            ));
        }
        PageResult<LegalHoldDTO> page = service.list("tenant_01", null, 10);
        assertThat(page.items()).hasSize(3);
    }

    @Test
    void createWritesAuditEvent() {
        service.create(new CreateLegalHoldCommand(
            "tenant_01", LegalHoldScopeType.MEETING, "mtg_01",
            "regulator inquiry", "user_compliance", null,
            "req_01", "trace_01"
        ));
        assertThat(audit.entries).hasSize(1);
        AuditEventLogger.AuditEntry e = audit.entries.get(0);
        assertThat(e.action()).isEqualTo(AuditAction.LEGAL_HOLD_PLACE);
        assertThat(e.resourceType()).isEqualTo("LEGAL_HOLD");
        assertThat(e.actorUserId()).isEqualTo("user_compliance");
    }

    @Test
    void releaseWritesAuditEvent() {
        LegalHoldDTO created = service.create(new CreateLegalHoldCommand(
            "tenant_01", LegalHoldScopeType.MEETING, "mtg_01",
            "x", "user_compliance", null, "req_01", "trace_01"
        ));
        audit.entries.clear();

        service.release("tenant_01", created.legalHoldId(), "user_admin", "case closed");

        assertThat(audit.entries).hasSize(1);
        AuditEventLogger.AuditEntry e = audit.entries.get(0);
        assertThat(e.action()).isEqualTo(AuditAction.LEGAL_HOLD_RELEASE);
        assertThat(e.actorUserId()).isEqualTo("user_admin");
    }

    private static class RecordingAuditLogger implements AuditEventLogger {
        final List<AuditEntry> entries = new ArrayList<>();
        @Override public void log(AuditEntry entry) { entries.add(entry); }
    }

    /** Hand-rolled in-memory repository to keep tests free of Spring + JDBC. */
    private static class InMemoryLegalHoldRepository implements LegalHoldRepository {
        final Map<String, LegalHold> rows = new HashMap<>();

        @Override public void save(LegalHold hold) { rows.put(hold.id(), hold); }
        @Override public void update(LegalHold hold) { rows.put(hold.id(), hold); }

        @Override
        public Optional<LegalHold> findById(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<LegalHold> findActive(
            String tenantId, LegalHoldScopeType scopeType, String scopeId
        ) {
            return rows.values().stream()
                .filter(h -> h.isActive()
                    && h.scopeType() == scopeType && scopeId.equals(h.scopeId()))
                .findFirst();
        }

        @Override
        public PageResult<LegalHold> listByTenant(String tenantId, String cursor, int limit) {
            List<LegalHold> all = rows.values().stream().toList();
            return new PageResult<>(all, new PageResult.PageInfo(null, false, limit));
        }
    }
}
