package com.meeting.api.infrastructure.gateway.aiworker;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Minimal consecutive-failure circuit breaker for best-effort ai-worker
 * calls (hand-rolled on purpose — no resilience library on the classpath,
 * matching the house style of {@code RagRateLimiter}).
 *
 * <p>Semantics:
 * <ul>
 *   <li>CLOSED: calls pass; {@code failureThreshold} consecutive failures
 *       open the circuit.</li>
 *   <li>OPEN: calls are rejected until {@code openDuration} elapses, then
 *       exactly one probe is let through (HALF_OPEN).</li>
 *   <li>HALF_OPEN: probe success closes the circuit; probe failure re-opens
 *       it for a fresh {@code openDuration}. Concurrent calls while the
 *       probe is in flight are rejected.</li>
 * </ul>
 *
 * <p>A {@code failureThreshold <= 0} disables the breaker entirely
 * ({@link #tryAcquire()} always true, recording is a no-op) so operators
 * can turn it off via config without a code change.
 *
 * <p>This breaker tracks <em>availability</em> only: callers should record
 * a success when the downstream responded (even with a contract error) and
 * a failure only on unavailable/timeout outcomes.
 */
public final class AiWorkerCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoTime;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtNanos = 0L;
    private boolean probeInFlight = false;

    public AiWorkerCircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier nanoTime) {
        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
        this.nanoTime = nanoTime;
    }

    /** True if the call may proceed; false means short-circuit (circuit open). */
    public synchronized boolean tryAcquire() {
        if (failureThreshold <= 0) {
            return true;
        }
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (nanoTime.getAsLong() - openedAtNanos >= openNanos) {
                    state = State.HALF_OPEN;
                    probeInFlight = true;
                    return true;
                }
                return false;
            case HALF_OPEN:
                if (!probeInFlight) {
                    probeInFlight = true;
                    return true;
                }
                return false;
            default:
                return true;
        }
    }

    /** Record that the downstream responded (including contract errors). */
    public synchronized void recordSuccess() {
        if (failureThreshold <= 0) {
            return;
        }
        consecutiveFailures = 0;
        probeInFlight = false;
        state = State.CLOSED;
    }

    /** Record an unavailable/timeout outcome. */
    public synchronized void recordFailure() {
        if (failureThreshold <= 0) {
            return;
        }
        if (state == State.HALF_OPEN) {
            open();
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    public synchronized State state() {
        return state;
    }

    private void open() {
        state = State.OPEN;
        openedAtNanos = nanoTime.getAsLong();
        consecutiveFailures = 0;
        probeInFlight = false;
    }
}
