package com.meeting.api.app.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class MeetingApiMetrics {

    private static final String CALLBACK_TOTAL = "meeting.api.callback.events";
    private static final String CALLBACK_TIMER = "meeting.api.callback.duration";
    private static final String SSE_OPENED = "meeting.api.sse.opened";
    private static final String SSE_EVENTS = "meeting.api.sse.events";
    private static final String OUTBOX_PUBLISHED = "meeting.api.outbox.published";
    private static final String OUTBOX_FAILED = "meeting.api.outbox.failed";
    private static final String LEASE_SCANNER_RUNS = "meeting.api.lease_scanner.runs";
    private static final String LEASE_SCANNER_ORPHANED = "meeting.api.lease_scanner.orphaned";
    private static final String AI_WORKER_CALLS = "meeting.api.aiworker.calls";
    private static final String EXPORT_RENDERS = "meeting.api.export.renders";
    private static final String LEGAL_HOLD_BLOCKS = "meeting.api.legal_hold.blocks";
    private static final String AUDIT_EVENTS = "meeting.api.audit.events";
    private static final String KMS_FAILURES = "meeting.api.kms.encrypt_failures";
    private static final String TENANT_CONTEXT_MISSING = "meeting.api.tenant_context.missing";
    private static final String RAG_PHASE_DURATION = "rag.query.phase.duration";
    private static final String RAG_RATE_LIMIT_BLOCKS = "meeting.api.rag.rate_limit_blocks";

    private final MeterRegistry registry;

    public MeetingApiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Counter callbackCounter(String outcome, String step) {
        return Counter.builder(CALLBACK_TOTAL)
            .tag("outcome", outcome)
            .tag("step", step == null ? "unknown" : step)
            .register(registry);
    }

    public Timer callbackTimer(String operation) {
        return Timer.builder(CALLBACK_TIMER)
            .tag("operation", operation)
            .publishPercentileHistogram()
            .register(registry);
    }

    public Counter sseOpenedCounter() {
        return Counter.builder(SSE_OPENED).register(registry);
    }

    public Counter sseEventCounter(String eventType) {
        return Counter.builder(SSE_EVENTS)
            .tag("eventType", eventType == null ? "unknown" : eventType)
            .register(registry);
    }

    /**
     * Per-phase timer for the RAG query pipeline. Phase tag values are
     * authoritative: {@code authorize}, {@code embed}, {@code retrieve},
     * {@code authorize_filter}, {@code rerank}, {@code llm}, {@code cite}.
     *
     * <p>Phase 8 (final-check.md B1) — exposed as
     * {@code rag_query_phase_duration_seconds_bucket{phase=...}} in
     * Prometheus to drive p95 alerts.
     */
    public Timer ragQueryPhaseTimer(String phase) {
        return Timer.builder(RAG_PHASE_DURATION)
            .tag("phase", phase == null ? "unknown" : phase)
            .publishPercentileHistogram()
            .register(registry);
    }

    /**
     * Counts RAG queries rejected with {@code RAG_RATE_LIMITED}. {@code key}
     * is a coarse bucket identifier — defaults to {@code "tenant_user"} —
     * to avoid high-cardinality tag explosion.
     */
    public Counter ragRateLimitBlocksCounter(String key) {
        return Counter.builder(RAG_RATE_LIMIT_BLOCKS)
            .tag("key", key == null ? "unknown" : key)
            .register(registry);
    }

    public Counter outboxPublishedCounter(String eventType) {
        return Counter.builder(OUTBOX_PUBLISHED)
            .tag("eventType", eventType == null ? "unknown" : eventType)
            .register(registry);
    }

    public Counter outboxFailedCounter(String eventType, String errorCode) {
        return Counter.builder(OUTBOX_FAILED)
            .tag("eventType", eventType == null ? "unknown" : eventType)
            .tag("errorCode", errorCode == null ? "unknown" : errorCode)
            .register(registry);
    }

    public Counter leaseScannerRunCounter() {
        return Counter.builder(LEASE_SCANNER_RUNS).register(registry);
    }

    public Counter leaseScannerOrphanedCounter() {
        return Counter.builder(LEASE_SCANNER_ORPHANED).register(registry);
    }

    /**
     * Counts ai-worker internal API call outcomes. {@code operation} is the
     * endpoint family ({@code embed}, {@code rerank}, {@code models},
     * {@code warmup}); {@code outcome} is one of {@code called},
     * {@code success}, {@code unavailable}, {@code contract_error},
     * {@code circuit_open}.
     */
    public Counter aiWorkerCallCounter(String operation, String outcome) {
        return Counter.builder(AI_WORKER_CALLS)
            .tag("operation", operation == null ? "unknown" : operation)
            .tag("outcome", outcome == null ? "unknown" : outcome)
            .register(registry);
    }

    /**
     * Counts export render outcomes by format. {@code format} is one of
     * {@code MARKDOWN}, {@code DOCX}, {@code PDF}; {@code outcome} is one
     * of {@code succeeded}, {@code failed}, {@code cancelled},
     * {@code revoked}.
     */
    public Counter exportRendersCounter(String format, String outcome) {
        return Counter.builder(EXPORT_RENDERS)
            .tag("format", format == null ? "unknown" : format)
            .tag("outcome", outcome == null ? "unknown" : outcome)
            .register(registry);
    }

    /**
     * Counts operations rejected because the target was under an active
     * legal hold. {@code operation} is the verb being blocked
     * ({@code delete_meeting}, {@code delete_document},
     * {@code create_export}, ...).
     */
    public Counter legalHoldBlocksCounter(String operation) {
        return Counter.builder(LEGAL_HOLD_BLOCKS)
            .tag("operation", operation == null ? "unknown" : operation)
            .register(registry);
    }

    /**
     * Counts audit events appended. {@code action} matches
     * {@link com.meeting.api.client.enums.AuditAction}; {@code result}
     * matches {@link com.meeting.api.client.enums.AuditResult}.
     */
    public Counter auditEventCounter(String action, String result) {
        return Counter.builder(AUDIT_EVENTS)
            .tag("action", action == null ? "unknown" : action)
            .tag("result", result == null ? "unknown" : result)
            .register(registry);
    }

    /**
     * Counts KMS envelope-encrypt failures by operation
     * ({@code wrap}, {@code unwrap}, {@code rotate}). Critical alert
     * threshold lives in the Prometheus rules.
     */
    public Counter kmsEncryptFailuresCounter(String operation) {
        return Counter.builder(KMS_FAILURES)
            .tag("operation", operation == null ? "unknown" : operation)
            .register(registry);
    }

    /**
     * Counts requests that reached production code paths without a
     * tenant context. Should be 0; non-zero indicates a missing filter.
     */
    public Counter tenantContextMissingCounter(String path) {
        return Counter.builder(TENANT_CONTEXT_MISSING)
            .tag("path", path == null ? "unknown" : path)
            .register(registry);
    }
}
