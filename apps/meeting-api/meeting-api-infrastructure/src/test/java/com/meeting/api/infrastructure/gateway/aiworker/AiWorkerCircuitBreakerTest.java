package com.meeting.api.infrastructure.gateway.aiworker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiWorkerCircuitBreakerTest {

    private final AtomicLong nanos = new AtomicLong(0);

    private AiWorkerCircuitBreaker breaker(int threshold, Duration open) {
        return new AiWorkerCircuitBreaker(threshold, open, nanos::get);
    }

    @Test
    void staysClosedBelowThreshold() {
        AiWorkerCircuitBreaker b = breaker(3, Duration.ofSeconds(30));
        b.recordFailure();
        b.recordFailure();
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.state()).isEqualTo(AiWorkerCircuitBreaker.State.CLOSED);
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        AiWorkerCircuitBreaker b = breaker(3, Duration.ofSeconds(30));
        b.recordFailure();
        b.recordFailure();
        b.recordSuccess();
        b.recordFailure();
        b.recordFailure();
        assertThat(b.state()).isEqualTo(AiWorkerCircuitBreaker.State.CLOSED);
        assertThat(b.tryAcquire()).isTrue();
    }

    @Test
    void opensAtThresholdAndRejectsUntilCooldownElapses() {
        AiWorkerCircuitBreaker b = breaker(3, Duration.ofSeconds(30));
        b.recordFailure();
        b.recordFailure();
        b.recordFailure();
        assertThat(b.state()).isEqualTo(AiWorkerCircuitBreaker.State.OPEN);
        assertThat(b.tryAcquire()).isFalse();

        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(b.tryAcquire()).isFalse();
    }

    @Test
    void allowsSingleProbeAfterCooldownAndClosesOnProbeSuccess() {
        AiWorkerCircuitBreaker b = breaker(1, Duration.ofSeconds(30));
        b.recordFailure();
        assertThat(b.tryAcquire()).isFalse();

        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.state()).isEqualTo(AiWorkerCircuitBreaker.State.HALF_OPEN);
        // Second caller while the probe is in flight is rejected.
        assertThat(b.tryAcquire()).isFalse();

        b.recordSuccess();
        assertThat(b.state()).isEqualTo(AiWorkerCircuitBreaker.State.CLOSED);
        assertThat(b.tryAcquire()).isTrue();
    }

    @Test
    void probeFailureReopensForAFreshCooldown() {
        AiWorkerCircuitBreaker b = breaker(1, Duration.ofSeconds(30));
        b.recordFailure();
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(b.tryAcquire()).isTrue();

        b.recordFailure();
        assertThat(b.state()).isEqualTo(AiWorkerCircuitBreaker.State.OPEN);
        assertThat(b.tryAcquire()).isFalse();

        // Fresh cooldown starts at the probe failure, not the original open.
        nanos.addAndGet(Duration.ofSeconds(30).toNanos());
        assertThat(b.tryAcquire()).isTrue();
    }

    @Test
    void zeroThresholdDisablesTheBreaker() {
        AiWorkerCircuitBreaker b = breaker(0, Duration.ofSeconds(30));
        for (int i = 0; i < 10; i++) {
            b.recordFailure();
        }
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.state()).isEqualTo(AiWorkerCircuitBreaker.State.CLOSED);
    }
}
