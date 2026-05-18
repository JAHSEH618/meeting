package com.meeting.api.adapter.rag;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory token-bucket rate limiter for {@code POST /api/rag/query}.
 *
 * <p>Phase 8 hardening (final-check.md B2). Keyed by
 * {@code tenantId:userId} so a noisy user can't starve a tenant peer.
 * Replenishment uses {@link System#nanoTime()} so it is monotonic
 * regardless of wall-clock skew.
 *
 * <p>This is intentionally process-local — RAG queries are read-heavy
 * and cheap to repeat, so cross-replica coordination would add cost
 * without changing the protection guarantee. When/if a stricter cap
 * is needed (e.g. per-tenant cluster-wide budget), the same interface
 * can be backed by a Redis token bucket without changing call sites.
 */
@Component
public class RagRateLimiter {

    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int ratePerMinute;
    private final int burstCapacity;

    public RagRateLimiter(
        @Value("${meeting.rag.query.rate-limit-rpm:60}") int ratePerMinute,
        @Value("${meeting.rag.query.rate-limit-burst:10}") int burstCapacity
    ) {
        if (ratePerMinute <= 0) {
            throw new IllegalArgumentException("rate-limit-rpm must be > 0");
        }
        if (burstCapacity <= 0) {
            throw new IllegalArgumentException("rate-limit-burst must be > 0");
        }
        this.ratePerMinute = ratePerMinute;
        this.burstCapacity = burstCapacity;
    }

    /**
     * Try to consume one token. Returns {@code true} when allowed.
     * Callers translate {@code false} to {@code RAG_RATE_LIMITED} 429.
     */
    public boolean tryAcquire(String tenantId, String userId) {
        String key = (tenantId == null ? "anon" : tenantId)
            + ":" + (userId == null ? "anon" : userId);
        return buckets
            .computeIfAbsent(key, k -> new TokenBucket(burstCapacity, ratePerMinute))
            .tryConsume();
    }

    public int ratePerMinute() {
        return ratePerMinute;
    }

    public int burstCapacity() {
        return burstCapacity;
    }

    /** Minimal token bucket — synchronized because the critical section is trivial. */
    static final class TokenBucket {
        private final double capacity;
        private final double nanosPerToken;
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int capacity, int ratePerMinute) {
            this.capacity = capacity;
            this.nanosPerToken = 60_000_000_000.0 / ratePerMinute;
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed / nanosPerToken);
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
