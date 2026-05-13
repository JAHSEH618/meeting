package com.meeting.api.app.common;

import java.util.function.Supplier;

public interface TenantScopedTransaction {
    <T> T execute(String tenantId, String userId, String requestId, Supplier<T> callback);

    void executeWithoutResult(String tenantId, String userId, String requestId, Runnable callback);

    static TenantScopedTransaction immediate() {
        return new TenantScopedTransaction() {
            @Override
            public <T> T execute(String tenantId, String userId, String requestId, Supplier<T> callback) {
                return callback.get();
            }

            @Override
            public void executeWithoutResult(String tenantId, String userId, String requestId, Runnable callback) {
                callback.run();
            }
        };
    }
}
