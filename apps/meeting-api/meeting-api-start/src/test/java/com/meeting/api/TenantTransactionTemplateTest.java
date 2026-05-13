package com.meeting.api;

import com.meeting.api.infrastructure.tenant.TenantSessionContext;
import com.meeting.api.infrastructure.tenant.TenantTransactionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantTransactionTemplateTest {

    @Test
    void setsAndResetsTenantContextInsideTransaction() {
        CapturingTenantSessionContext context = new CapturingTenantSessionContext();
        TenantTransactionTemplate template = new TenantTransactionTemplate(new TransactionTemplate(new NoopTransactionManager()), context);

        String result = template.execute("tenant_01", "user_01", "req_01", () -> {
            context.events.add("callback");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(context.events).containsExactly(
            "set:tenant_01:user_01:req_01",
            "callback",
            "reset"
        );
    }

    private static final class CapturingTenantSessionContext extends TenantSessionContext {
        private final List<String> events = new ArrayList<>();

        private CapturingTenantSessionContext() {
            super(null);
        }

        @Override
        public void set(String tenantId, String userId, String requestId) {
            events.add("set:" + tenantId + ":" + userId + ":" + requestId);
        }

        @Override
        public void reset() {
            events.add("reset");
        }
    }

    private static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
