package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.app.task.ProcessingTaskLeaseScanner;
import com.meeting.api.infrastructure.mq.OutboxPublisher;
import com.meeting.api.start.config.OutboxPublisherConfig;
import com.meeting.api.start.config.ProcessingTaskLeaseScannerConfig;
import com.meeting.api.start.config.SchedulingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherConfigContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(OutboxSchedulerSlice.class)
        .withPropertyValues(
            "meeting.outbox-publisher.enabled=true",
            "meeting.lease-scanner.enabled=false",
            "meeting.tenants.active=tenant_default"
        );

    @Test
    void keepsOutboxScheduledWhenProcessingTaskLeaseScannerIsDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxPublisherConfig.class);
            assertThat(context).doesNotHaveBean(ProcessingTaskLeaseScanner.class);
            assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                .isNotEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        SchedulingConfig.class,
        OutboxPublisherConfig.class,
        ProcessingTaskLeaseScannerConfig.class
    })
    static class OutboxSchedulerSlice {

        @Bean
        OutboxPublisher outboxPublisher() {
            return new OutboxPublisher(null, null, null, null, 100, 5) {
                @Override
                public int publishPending(String tenantId) {
                    return 0;
                }
            };
        }

        @Bean
        TenantScopedTransaction tenantScopedTransaction() {
            return TenantScopedTransaction.immediate();
        }
    }
}
