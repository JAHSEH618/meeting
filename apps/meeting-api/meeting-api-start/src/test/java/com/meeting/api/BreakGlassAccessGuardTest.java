package com.meeting.api;

import com.meeting.api.app.breakglass.BreakGlassAccessGuard;
import com.meeting.api.client.enums.AuditAction;
import com.meeting.api.domain.audit.AuditEventLogger;
import com.meeting.api.domain.breakglass.BreakGlassEvaluationPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BreakGlassAccessGuardTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T14:00:00Z");

    private ToggleableEvaluator evaluator;
    private RecordingAudit audit;
    private BreakGlassAccessGuard guard;

    @BeforeEach
    void setUp() {
        evaluator = new ToggleableEvaluator();
        audit = new RecordingAudit();
        guard = new BreakGlassAccessGuard(
            evaluator, audit, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)
        );
    }

    @Test
    void returnsTrueAndAuditsWhenGrantActive() {
        evaluator.activeKeys.add("tenant_01:user_42:MEETING:mtg_01");

        boolean granted = guard.checkAccess(
            "tenant_01", "user_42",
            "MEETING", "mtg_01",
            "/api/meetings/mtg_01", "trace_01"
        );

        assertThat(granted).isTrue();
        assertThat(audit.entries).hasSize(1);
        AuditEventLogger.AuditEntry entry = audit.entries.get(0);
        assertThat(entry.action()).isEqualTo(AuditAction.BREAK_GLASS_ACCESS);
        assertThat(entry.resourceType()).isEqualTo("MEETING");
        assertThat(entry.resourceId()).isEqualTo("mtg_01");
        assertThat(entry.actorUserId()).isEqualTo("user_42");
        assertThat(entry.traceId()).isEqualTo("trace_01");
        assertThat(entry.payload()).containsEntry("resourcePath", "/api/meetings/mtg_01");
    }

    @Test
    void returnsFalseAndDoesNotAuditWhenNoGrant() {
        boolean granted = guard.checkAccess(
            "tenant_01", "user_42",
            "MEETING", "mtg_01",
            "/api/meetings/mtg_01", "trace_01"
        );
        assertThat(granted).isFalse();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    void emptyResourcePathHandled() {
        evaluator.activeKeys.add("tenant_01:user_42:DOCUMENT:doc_01");
        boolean granted = guard.checkAccess(
            "tenant_01", "user_42",
            "DOCUMENT", "doc_01",
            null, null
        );
        assertThat(granted).isTrue();
        assertThat(audit.entries.get(0).payload()).containsEntry("resourcePath", "");
    }

    private static class ToggleableEvaluator implements BreakGlassEvaluationPort {
        final Set<String> activeKeys = new HashSet<>();
        @Override
        public boolean hasActiveAccess(
            String tenantId, String userId, String scopeType, String scopeId, OffsetDateTime at
        ) {
            return activeKeys.contains(tenantId + ":" + userId + ":" + scopeType + ":" + scopeId);
        }
    }

    private static class RecordingAudit implements AuditEventLogger {
        final List<AuditEntry> entries = new ArrayList<>();
        @Override public void log(AuditEntry entry) { entries.add(entry); }
    }
}
