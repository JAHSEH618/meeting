package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.WorkerDagDoneRecoveryScanner;
import com.meeting.api.domain.task.ProcessingTask;
import com.meeting.api.domain.task.ProcessingTaskRepository;
import com.meeting.api.start.config.SchedulingConfig;
import com.meeting.api.start.config.WorkerDagDoneRecoveryScannerConfig;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerDagDoneRecoveryScannerConfigContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(RecoverySchedulerSlice.class)
        .withPropertyValues("meeting.tenants.active=tenant_default");

    @Test
    void registersScannerAndSchedulingByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkerDagDoneRecoveryScannerConfig.class);
            assertThat(context).hasSingleBean(WorkerDagDoneRecoveryScanner.class);
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                .isNotEmpty();
        });
    }

    @Test
    void disablePropertyRemovesScanner() {
        contextRunner
            .withPropertyValues("meeting.worker-dag-recovery.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(WorkerDagDoneRecoveryScannerConfig.class);
                assertThat(context).doesNotHaveBean(WorkerDagDoneRecoveryScanner.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        SchedulingConfig.class,
        WorkerDagDoneRecoveryScannerConfig.class
    })
    static class RecoverySchedulerSlice {

        @Bean
        ProcessingTaskRepository processingTaskRepository() {
            return new ProcessingTaskRepository() {
                @Override
                public ProcessingTask save(ProcessingTask task) {
                    return task;
                }

                @Override
                public Optional<ProcessingTask> findById(String tenantId, String taskId) {
                    return Optional.empty();
                }

                @Override
                public Optional<ProcessingTask> findByIdForUpdate(String tenantId, String taskId) {
                    return Optional.empty();
                }

                @Override
                public Optional<ProcessingTask> findLatestByMeetingId(String tenantId, String meetingId) {
                    return Optional.empty();
                }

                @Override
                public List<ProcessingTaskRepository.ExpiredLease> findExpiredLeases(
                    String tenantId, OffsetDateTime now, int limit
                ) {
                    return List.of();
                }
            };
        }

        @Bean
        TenantScopedTransaction tenantScopedTransaction() {
            return TenantScopedTransaction.immediate();
        }
    }
}
