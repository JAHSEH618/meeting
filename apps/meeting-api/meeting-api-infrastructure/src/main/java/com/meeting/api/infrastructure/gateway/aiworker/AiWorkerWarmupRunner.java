package com.meeting.api.infrastructure.gateway.aiworker;

import com.meeting.api.app.observability.MeetingApiMetrics;
import com.meeting.api.domain.rag.AiWorkerContractException;
import com.meeting.api.domain.rag.AiWorkerUnavailableException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * On {@link ApplicationReadyEvent} fires a single, fire-and-forget call to
 * {@code POST /internal/models/warmup} so the very first
 * {@code /internal/embed} or {@code /internal/rerank} request does not
 * stall behind the 5-15s real-mode model load. In fake mode this is a
 * cheap idempotent confirmation that the runtimes are READY.
 *
 * <p>Disabled by setting {@code meeting.security.ai-worker.warmup-enabled=false}.
 * The HTTP call honors {@code warmup-timeout-ms} (default 5000) and any
 * failure is logged + counted, never propagated — refusing to start the
 * app because ai-worker is briefly down would create a circular
 * dependency between meeting-api and ai-worker readiness.
 */
@Component
public class AiWorkerWarmupRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(AiWorkerWarmupRunner.class);
    private static final String OPERATION = "warmup";

    private final AiWorkerInternalClient client;
    private final AiWorkerInternalProperties properties;
    private final MeetingApiMetrics metrics;
    private final boolean enabled;
    private final ExecutorService executor;

    public AiWorkerWarmupRunner(
        AiWorkerInternalClient client,
        AiWorkerInternalProperties properties,
        MeetingApiMetrics metrics,
        @Value("${meeting.security.ai-worker.warmup-enabled:true}") boolean enabled
    ) {
        this(client, properties, metrics, enabled, defaultExecutor());
    }

    /** Visible for tests so the warmup can run synchronously. */
    public AiWorkerWarmupRunner(
        AiWorkerInternalClient client,
        AiWorkerInternalProperties properties,
        MeetingApiMetrics metrics,
        boolean enabled,
        ExecutorService executor
    ) {
        this.client = client;
        this.properties = properties;
        this.metrics = metrics;
        this.enabled = enabled;
        this.executor = executor;
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ai-worker-warmup");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!enabled) {
            log.info("ai-worker warmup disabled by config");
            return;
        }
        executor.submit(this::warmup);
    }

    /** Visible for tests so the work can be invoked synchronously. */
    public void warmup() {
        metrics.aiWorkerCallCounter(OPERATION, "called").increment();
        try {
            client.call(
                "POST",
                "/models/warmup",
                null,
                "system",
                "boot-warmup",
                "boot-warmup",
                properties.warmupTimeoutMs()
            );
            metrics.aiWorkerCallCounter(OPERATION, "success").increment();
            log.info("ai-worker warmup scheduled");
        } catch (AiWorkerUnavailableException e) {
            metrics.aiWorkerCallCounter(OPERATION, "unavailable").increment();
            log.warn("ai-worker warmup unavailable: {} ({})", e.getMessage(), e.errorCode());
        } catch (AiWorkerContractException e) {
            metrics.aiWorkerCallCounter(OPERATION, "contract_error").increment();
            log.error("ai-worker warmup contract error (check HMAC secret config): {} ({})",
                e.getMessage(), e.errorCode());
        } catch (RuntimeException e) {
            metrics.aiWorkerCallCounter(OPERATION, "error").increment();
            log.error("ai-worker warmup unexpected error", e);
        }
    }
}
