package com.meeting.api.adapter.meeting;

/**
 * Thread-local holder for the current tenant context.
 * Set by the TenantContextFilter (or auth interceptor) on every request from the JWT claims.
 *
 * spec.md §2.1 rule: "current tenant 缺失时 fail closed" — any call to currentTenantId()
 * when the context is not set MUST throw TenantContextMissingException.
 */
public final class TenantContextHolder {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_REQUEST = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(String tenantId, String userId, String requestId) {
        CURRENT_TENANT.set(tenantId);
        CURRENT_USER.set(userId);
        CURRENT_REQUEST.set(requestId);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_USER.remove();
        CURRENT_REQUEST.remove();
    }

    /** @throws TenantContextMissingException if no tenant context is set */
    public static String currentTenantId() {
        String id = CURRENT_TENANT.get();
        if (id == null || id.isBlank()) {
            throw new TenantContextMissingException("Tenant context is not set — request must be authenticated");
        }
        return id;
    }

    public static String currentUserId() {
        return CURRENT_USER.get();
    }

    public static String currentRequestId() {
        return CURRENT_REQUEST.get();
    }
}
