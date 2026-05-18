package com.meeting.api;

import com.meeting.api.app.observability.MeetingApiMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 (final-check.md B3) — assert {@link MeetingApiMetrics} exposes
 * the RAG-phase timer and rate-limit counter with the right names and
 * tag keys so Prometheus alerts can stay attached to them.
 */
class MeetingApiMetricsTest {

    @Test
    void ragQueryPhaseTimerEmitsRightNameAndTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeetingApiMetrics metrics = new MeetingApiMetrics(registry);

        Timer.Sample sample = Timer.start(registry);
        sample.stop(metrics.ragQueryPhaseTimer("rerank"));

        Timer timer = registry.find("rag.query.phase.duration").tag("phase", "rerank").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void ragQueryPhaseTimerRegistersAllSpecPhases() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeetingApiMetrics metrics = new MeetingApiMetrics(registry);

        for (String phase : new String[]{
            "authorize", "embed", "retrieve", "authorize_filter",
            "rerank", "llm", "cite"
        }) {
            Timer.Sample sample = Timer.start(registry);
            sample.stop(metrics.ragQueryPhaseTimer(phase));
            Timer timer = registry.find("rag.query.phase.duration").tag("phase", phase).timer();
            assertThat(timer).as("timer for phase=%s", phase).isNotNull();
            assertThat(timer.count()).as("timer count for phase=%s", phase).isEqualTo(1);
        }
    }

    @Test
    void ragRateLimitBlocksCounterDefaultsKeyTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeetingApiMetrics metrics = new MeetingApiMetrics(registry);

        metrics.ragRateLimitBlocksCounter("tenant_user").increment();
        metrics.ragRateLimitBlocksCounter("tenant_user").increment();

        assertThat(
            registry.counter("meeting.api.rag.rate_limit_blocks", "key", "tenant_user").count()
        ).isEqualTo(2.0);
    }
}
