package com.meeting.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.domain.task.CallbackNonceRepository;
import com.meeting.api.start.config.CallbackNonceCleanupConfig;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallbackNonceCleanupConfigTest {

    private static final class CountingNonceRepository implements CallbackNonceRepository {
        final List<OffsetDateTime> cleanupCalls = new ArrayList<>();
        int deletedPerCall = 5;
        boolean throwOnFirstCall = false;
        private boolean thrown = false;

        @Override
        public boolean exists(String tenantId, String nonce) {
            return false;
        }

        @Override
        public boolean record(String tenantId, String nonce, String workerId, String taskId, String stepName) {
            return true;
        }

        @Override
        public int cleanupExpired(OffsetDateTime before) {
            if (throwOnFirstCall && !thrown) {
                thrown = true;
                throw new IllegalStateException("simulated cleanup failure");
            }
            cleanupCalls.add(before);
            return deletedPerCall;
        }
    }

    @Test
    void cleansUpOncePerActiveTenant() {
        CountingNonceRepository repo = new CountingNonceRepository();
        CallbackNonceCleanupConfig config = new CallbackNonceCleanupConfig(
            repo,
            TenantScopedTransaction.immediate(),
            "tenant_a,tenant_b,tenant_c"
        );

        config.cleanupExpiredNonces();

        assertThat(repo.cleanupCalls).hasSize(3);
        // All tenants share the same cutoff so a slow loop can't skew TTLs.
        assertThat(repo.cleanupCalls.stream().distinct()).hasSize(1);
    }

    @Test
    void aFailingTenantDoesNotAbortTheOthers() {
        CountingNonceRepository repo = new CountingNonceRepository();
        repo.throwOnFirstCall = true;
        CallbackNonceCleanupConfig config = new CallbackNonceCleanupConfig(
            repo,
            TenantScopedTransaction.immediate(),
            "tenant_a,tenant_b"
        );

        config.cleanupExpiredNonces();

        assertThat(repo.cleanupCalls).hasSize(1);
    }

    @Test
    void noTenantsConfiguredIsANoOp() {
        CountingNonceRepository repo = new CountingNonceRepository();
        CallbackNonceCleanupConfig config = new CallbackNonceCleanupConfig(
            repo,
            TenantScopedTransaction.immediate(),
            ""
        );

        config.cleanupExpiredNonces();

        assertThat(repo.cleanupCalls).isEmpty();
    }
}
