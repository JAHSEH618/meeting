package com.meeting.api;

import com.meeting.api.domain.task.CallbackNonceRepository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现的 Nonce Repository，用于测试
 */
public class InMemoryCallbackNonceRepository implements CallbackNonceRepository {

    private final Map<String, NonceRecord> nonces = new ConcurrentHashMap<>();

    @Override
    public boolean exists(String tenantId, String nonce) {
        return nonces.containsKey(key(tenantId, nonce));
    }

    @Override
    public boolean record(String tenantId, String nonce, String workerId, String taskId, String stepName) {
        String key = key(tenantId, nonce);
        NonceRecord existing = nonces.putIfAbsent(
            key,
            new NonceRecord(tenantId, nonce, workerId, taskId, stepName, OffsetDateTime.now())
        );
        return existing == null;
    }

    @Override
    public int cleanupExpired(OffsetDateTime before) {
        int count = 0;
        for (Map.Entry<String, NonceRecord> entry : nonces.entrySet()) {
            if (entry.getValue().expiresAt.isBefore(before)) {
                nonces.remove(entry.getKey());
                count++;
            }
        }
        return count;
    }

    private String key(String tenantId, String nonce) {
        return tenantId + ":" + nonce;
    }

    private record NonceRecord(
        String tenantId,
        String nonce,
        String workerId,
        String taskId,
        String stepName,
        OffsetDateTime expiresAt
    ) {}
}
