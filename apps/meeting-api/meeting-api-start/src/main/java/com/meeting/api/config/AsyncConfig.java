package com.meeting.api.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async configuration for event listeners and background tasks.
 *
 * <p>The {@code workerPhaseCompletedExecutor} is used by {@code WorkerPhaseCompletedListener}
 * to drive Java LLM phase (SUMMARY / EXTRACTION) without blocking callback responses.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    /**
     * Dedicated executor for {@code WorkerPhaseCompletedListener}.
     *
     * <ul>
     *   <li>Core pool: 4 threads (allows concurrent processing of multiple tasks)</li>
     *   <li>Max pool: 8 threads (burst capacity for high-volume callback spikes)</li>
     *   <li>Queue capacity: 100 (prevents unbounded memory growth; rejected tasks log warnings)</li>
     * </ul>
     */
    @Bean(name = "workerPhaseCompletedExecutor")
    public Executor workerPhaseCompletedExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("worker-phase-completed-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
