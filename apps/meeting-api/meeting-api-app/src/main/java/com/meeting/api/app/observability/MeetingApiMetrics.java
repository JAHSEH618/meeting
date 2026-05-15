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
     * {@code success}, {@code unavailable}, {@code contract_error}.
     */
    public Counter aiWorkerCallCounter(String operation, String outcome) {
        return Counter.builder(AI_WORKER_CALLS)
            .tag("operation", operation == null ? "unknown" : operation)
            .tag("outcome", outcome == null ? "unknown" : outcome)
            .register(registry);
    }
}
