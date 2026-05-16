package com.meeting.api.app.rag;

import com.meeting.api.client.rag.RagAnswerDTO;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Process-local, TTL- and coverage-evicting {@link RagAnswerCache}.
 *
 * <p>One {@link ConcurrentHashMap} holds the entries; two secondary
 * indices (per-meeting and per-document) hold reverse pointers from
 * an owner key back to the cache keys whose coverage referenced it.
 * Invalidation is therefore O(coverage) rather than O(cacheSize),
 * which matters because the chunk-reindex listener runs on the hot
 * upload / edit path.
 *
 * <p>Size is bounded only loosely: on each {@link #store} we run a
 * cheap sweep that drops expired entries; if the cache still exceeds
 * {@code maxEntries}, we drop the oldest entries by stored-at
 * timestamp. There is no per-tenant fairness — this cache is a
 * best-effort latency optimisation, not a critical path.
 *
 * <p>Listens to {@link KnowledgeChunkReindexRequestedEvent} so the
 * cache drops automatically when {@code POST /api/rag/reindex/...}
 * commits. Other invalidation triggers (transcript edits, document
 * deletes) call {@link #invalidateMeeting} / {@link #invalidateDocument}
 * directly through the {@link RagAnswerCache} interface.
 */
@Component
public class InMemoryRagAnswerCache implements RagAnswerCache {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRagAnswerCache.class);

    private final ConcurrentHashMap<RagCacheKey, CachedEntry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<OwnerKey, Set<RagCacheKey>> meetingIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<OwnerKey, Set<RagCacheKey>> documentIndex = new ConcurrentHashMap<>();

    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;

    public InMemoryRagAnswerCache(
        Clock clock,
        @Value("${meeting.rag.cache.ttl-seconds:300}") long ttlSeconds,
        @Value("${meeting.rag.cache.max-entries:1024}") int maxEntries
    ) {
        this.clock = clock;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override
    public Optional<RagAnswerDTO> lookup(RagCacheKey key) {
        CachedEntry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(clock.instant())) {
            // Lazy eviction — drop the stale row so the next miss is cheap.
            remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.answer);
    }

    @Override
    public void store(RagCacheKey key, RagAnswerDTO answer, CacheCoverage coverage) {
        Instant now = clock.instant();
        CachedEntry fresh = new CachedEntry(answer, now, now.plus(ttl), coverage);
        CachedEntry previous = entries.put(key, fresh);
        if (previous != null) {
            unindex(key, previous.coverage);
        }
        index(key, coverage);
        if (entries.size() > maxEntries) {
            sweep(now);
        }
    }

    @Override
    public int invalidateMeeting(String tenantId, String meetingId) {
        return invalidateOwner(meetingIndex, new OwnerKey(tenantId, meetingId));
    }

    @Override
    public int invalidateDocument(String tenantId, String documentId) {
        return invalidateOwner(documentIndex, new OwnerKey(tenantId, documentId));
    }

    @Override
    public int invalidateTenant(String tenantId) {
        int dropped = 0;
        for (var e : new ArrayList<>(entries.entrySet())) {
            if (tenantId.equals(e.getKey().tenantId())) {
                remove(e.getKey(), e.getValue());
                dropped++;
            }
        }
        return dropped;
    }

    @Override
    public void clear() {
        entries.clear();
        meetingIndex.clear();
        documentIndex.clear();
    }

    /**
     * Drop cache entries whose coverage included a meeting / document that
     * was just re-chunked. Runs AFTER_COMMIT inside a transaction so we
     * don't drop entries when the producing transaction rolls back;
     * {@code fallbackExecution=true} keeps the invariant in non-transactional
     * code paths (tests, admin scripts) where there is no tx to gate on.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChunkReindex(KnowledgeChunkReindexRequestedEvent event) {
        if (event.meetingId() != null) {
            int dropped = invalidateMeeting(event.tenantId(), event.meetingId());
            if (dropped > 0) {
                log.info(
                    "rag_cache_invalidated_meeting tenant={} meeting={} dropped={}",
                    event.tenantId(), event.meetingId(), dropped
                );
            }
        }
        if (event.documentId() != null) {
            int dropped = invalidateDocument(event.tenantId(), event.documentId());
            if (dropped > 0) {
                log.info(
                    "rag_cache_invalidated_document tenant={} document={} dropped={}",
                    event.tenantId(), event.documentId(), dropped
                );
            }
        }
    }

    private int invalidateOwner(
        ConcurrentHashMap<OwnerKey, Set<RagCacheKey>> index, OwnerKey owner
    ) {
        Set<RagCacheKey> keys = index.remove(owner);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        int dropped = 0;
        // Snapshot to avoid CME if other invalidates touch the same set.
        for (RagCacheKey key : new ArrayList<>(keys)) {
            CachedEntry entry = entries.remove(key);
            if (entry != null) {
                // The owner index is already gone for this owner; clear out
                // any *other* owners that pointed at the same entry too.
                unindex(key, entry.coverage);
                dropped++;
            }
        }
        return dropped;
    }

    private void index(RagCacheKey key, CacheCoverage coverage) {
        for (String mtg : coverage.meetingIds()) {
            meetingIndex
                .computeIfAbsent(new OwnerKey(key.tenantId(), mtg), k -> ConcurrentHashMap.newKeySet())
                .add(key);
        }
        for (String doc : coverage.documentIds()) {
            documentIndex
                .computeIfAbsent(new OwnerKey(key.tenantId(), doc), k -> ConcurrentHashMap.newKeySet())
                .add(key);
        }
    }

    private void unindex(RagCacheKey key, CacheCoverage coverage) {
        for (String mtg : coverage.meetingIds()) {
            OwnerKey owner = new OwnerKey(key.tenantId(), mtg);
            Set<RagCacheKey> ks = meetingIndex.get(owner);
            if (ks != null) {
                ks.remove(key);
                if (ks.isEmpty()) {
                    meetingIndex.remove(owner, ks);
                }
            }
        }
        for (String doc : coverage.documentIds()) {
            OwnerKey owner = new OwnerKey(key.tenantId(), doc);
            Set<RagCacheKey> ks = documentIndex.get(owner);
            if (ks != null) {
                ks.remove(key);
                if (ks.isEmpty()) {
                    documentIndex.remove(owner, ks);
                }
            }
        }
    }

    private void remove(RagCacheKey key, CachedEntry entry) {
        // Use a guarded remove so we don't race with a concurrent store().
        if (entries.remove(key, entry)) {
            unindex(key, entry.coverage);
        }
    }

    /**
     * Drop expired rows; if still over the cap, drop the oldest. Called
     * under the contention of a single thread (the {@code store()} that
     * tripped the cap) so we don't need extra locking.
     */
    private void sweep(Instant now) {
        List<RagCacheKey> expired = new ArrayList<>();
        for (var e : entries.entrySet()) {
            if (e.getValue().isExpired(now)) {
                expired.add(e.getKey());
            }
        }
        for (RagCacheKey k : expired) {
            CachedEntry ent = entries.get(k);
            if (ent != null) remove(k, ent);
        }
        if (entries.size() <= maxEntries) {
            return;
        }
        // Still over: drop oldest stored-at first.
        List<java.util.Map.Entry<RagCacheKey, CachedEntry>> snapshot = new ArrayList<>(entries.entrySet());
        snapshot.sort(java.util.Comparator.comparing(e -> e.getValue().storedAt));
        int toDrop = entries.size() - maxEntries;
        for (int i = 0; i < toDrop && i < snapshot.size(); i++) {
            var e = snapshot.get(i);
            remove(e.getKey(), e.getValue());
        }
    }

    /** Test introspection. */
    public int size() {
        return entries.size();
    }

    /** Test introspection. */
    public Set<RagCacheKey> snapshotMeetingIndex(String tenantId, String meetingId) {
        Set<RagCacheKey> ks = meetingIndex.get(new OwnerKey(tenantId, meetingId));
        return ks == null ? Set.of() : new HashSet<>(ks);
    }

    private record OwnerKey(String tenantId, String ownerId) {
    }

    private static final class CachedEntry {
        final RagAnswerDTO answer;
        final Instant storedAt;
        final Instant expiresAt;
        final CacheCoverage coverage;

        CachedEntry(RagAnswerDTO answer, Instant storedAt, Instant expiresAt, CacheCoverage coverage) {
            this.answer = answer;
            this.storedAt = storedAt;
            this.expiresAt = expiresAt;
            this.coverage = coverage;
        }

        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
