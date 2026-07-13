package com.meeting.api;

import com.meeting.api.app.common.TenantScopedTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Test double for {@link TenantScopedTransaction} that runs callbacks inline
 * (like {@link TenantScopedTransaction#immediate()}) while recording each
 * invocation. Lets unit tests pin RLS-sensitive transaction boundaries:
 * which tenant a callback ran for, whether some collaborator was invoked
 * inside or outside a tenant-scoped transaction, and whether transactions
 * were nested (an inner execute joining an outer one defeats the
 * "no-TX LLM window" split).
 */
final class RecordingTenantTransaction implements TenantScopedTransaction {
    private final List<String> tenantIds = new ArrayList<>();
    private int depth;
    private int maxDepth;

    @Override
    public <T> T execute(String tenantId, String userId, String requestId, Supplier<T> callback) {
        tenantIds.add(tenantId);
        depth++;
        maxDepth = Math.max(maxDepth, depth);
        try {
            return callback.get();
        } finally {
            depth--;
        }
    }

    @Override
    public void executeWithoutResult(String tenantId, String userId, String requestId, Runnable callback) {
        execute(tenantId, userId, requestId, () -> {
            callback.run();
            return null;
        });
    }

    /** True while a callback passed to {@link #execute} is running. */
    boolean inTransaction() {
        return depth > 0;
    }

    /** Tenant ids in invocation order, one entry per execute call. */
    List<String> tenantIds() {
        return List.copyOf(tenantIds);
    }

    /** Total number of execute / executeWithoutResult invocations. */
    int executions() {
        return tenantIds.size();
    }

    /** Deepest observed nesting; 1 means no transaction ever nested in another. */
    int maxDepth() {
        return maxDepth;
    }
}
