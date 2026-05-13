package com.meeting.api.infrastructure.tenant;

import com.meeting.api.app.common.TenantScopedTransaction;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TenantTransactionTemplate implements TenantScopedTransaction {
    private final TransactionTemplate transactionTemplate;
    private final TenantSessionContext tenantSessionContext;

    public TenantTransactionTemplate(TransactionTemplate transactionTemplate, TenantSessionContext tenantSessionContext) {
        this.transactionTemplate = transactionTemplate;
        this.tenantSessionContext = tenantSessionContext;
    }

    @Override
    public <T> T execute(String tenantId, String userId, String requestId, Supplier<T> callback) {
        return transactionTemplate.execute(status -> {
            tenantSessionContext.set(tenantId, userId, requestId);
            try {
                return callback.get();
            } finally {
                tenantSessionContext.reset();
            }
        });
    }

    @Override
    public void executeWithoutResult(String tenantId, String userId, String requestId, Runnable callback) {
        execute(tenantId, userId, requestId, () -> {
            callback.run();
            return null;
        });
    }
}
