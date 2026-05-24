package com.meeting.api.start.config;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.infrastructure.mq.OutboxPublisher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives {@link OutboxPublisher#publishPending(String)} on a fixed delay,
 * one transaction per configured tenant. Each invocation goes through
 * {@link TenantScopedTransaction} so {@code app.tenant_id} is set before
 * the SELECT … FOR UPDATE SKIP LOCKED runs — otherwise the row-level
 * security policy on {@code domain_events_outbox} returns an empty
 * result and the queue silently stalls.
 *
 * <p>Disabled with {@code meeting.outbox-publisher.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "meeting.outbox-publisher",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class OutboxPublisherConfig {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisherConfig.class);

    private final OutboxPublisher outboxPublisher;
    private final TenantScopedTransaction tenantTx;
    private final List<String> tenantIds;

    public OutboxPublisherConfig(
        OutboxPublisher outboxPublisher,
        TenantScopedTransaction tenantTx,
        @Value("${meeting.tenants.active:${meeting.outbox-publisher.tenants:tenant_default}}")
            String tenantIdsCsv
    ) {
        this.outboxPublisher = outboxPublisher;
        this.tenantTx = tenantTx;
        this.tenantIds = ActiveTenantList.parse(tenantIdsCsv);
    }

    @Scheduled(
        fixedDelayString = "${meeting.outbox-publisher.interval-ms:2000}",
        initialDelayString = "${meeting.outbox-publisher.initial-delay-ms:5000}"
    )
    public void drainAllTenants() {
        if (tenantIds.isEmpty()) return;
        for (String tenantId : tenantIds) {
            try {
                int published = tenantTx.execute(
                    tenantId, "outbox-publisher", null,
                    () -> outboxPublisher.publishPending(tenantId)
                );
                if (published > 0) {
                    LOG.info("outbox_publisher_run tenant={} published={}", tenantId, published);
                }
            } catch (RuntimeException ex) {
                LOG.warn("outbox_publisher_failed tenant={} reason={}", tenantId, ex.toString());
            }
        }
    }
}
