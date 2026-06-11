package com.meeting.api;

import com.meeting.api.app.rag.InMemoryRagAnswerCache;
import com.meeting.api.app.rag.KnowledgeChunkReindexRequestedEvent;
import com.meeting.api.app.rag.RagAnswerCache;
import com.meeting.api.app.rag.RagAnswerCache.CacheCoverage;
import com.meeting.api.app.rag.RagAnswerCache.RagCacheKey;
import com.meeting.api.client.enums.RagAnswerCoverage;
import com.meeting.api.client.rag.RagAnswerDTO;
import com.meeting.api.client.rag.RagQueryScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRagAnswerCacheTest {

    @Test
    void lookupMissReturnsEmpty() {
        InMemoryRagAnswerCache cache = newCache();
        assertThat(cache.lookup(key("q1", RagQueryScope.EMPTY))).isEmpty();
    }

    @Test
    void storeThenLookupReturnsCachedAnswer() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey k = key("q1", RagQueryScope.EMPTY);
        RagAnswerDTO answer = answer("a1");
        cache.store(k, answer, new CacheCoverage(Set.of("mtg_a"), Set.of()));

        Optional<RagAnswerDTO> got = cache.lookup(k);
        assertThat(got).isPresent();
        assertThat(got.get().answer()).isEqualTo("a1");
    }

    @Test
    void differentUsersGetSeparateCacheSlotsEvenForSameQuestion() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey forUserA = new RagCacheKey(
            "tenant_01", "userA", "same question",
            RagQueryScope.EMPTY, 5, false
        );
        RagCacheKey forUserB = new RagCacheKey(
            "tenant_01", "userB", "same question",
            RagQueryScope.EMPTY, 5, false
        );
        cache.store(forUserA, answer("A's answer"), new CacheCoverage(Set.of("mtg_a"), Set.of()));
        cache.store(forUserB, answer("B's answer"), new CacheCoverage(Set.of("mtg_b"), Set.of()));

        assertThat(cache.lookup(forUserA).orElseThrow().answer()).isEqualTo("A's answer");
        assertThat(cache.lookup(forUserB).orElseThrow().answer()).isEqualTo("B's answer");
    }

    @Test
    void scopeOrderDoesNotFragmentTheCache() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey first = key("q", new RagQueryScope(List.of("mtg_b", "mtg_a"), List.of()));
        RagCacheKey second = key("q", new RagQueryScope(List.of("mtg_a", "mtg_b"), List.of()));

        // The canonicalisation inside RagCacheKey's compact ctor should
        // produce equal keys regardless of input order.
        assertThat(first).isEqualTo(second);
        cache.store(first, answer("once"), new CacheCoverage(Set.of(), Set.of()));
        assertThat(cache.lookup(second)).isPresent();
    }

    @Test
    void invalidateMeetingDropsEntriesWhoseCoverageHitsIt() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey forMtgA = key("q-A", RagQueryScope.EMPTY);
        RagCacheKey forMtgB = key("q-B", RagQueryScope.EMPTY);
        cache.store(forMtgA, answer("ans-A"), new CacheCoverage(Set.of("mtg_a"), Set.of()));
        cache.store(forMtgB, answer("ans-B"), new CacheCoverage(Set.of("mtg_b"), Set.of()));

        int dropped = cache.invalidateMeeting("tenant_01", "mtg_a");

        assertThat(dropped).isEqualTo(1);
        assertThat(cache.lookup(forMtgA)).isEmpty();
        assertThat(cache.lookup(forMtgB)).isPresent();
    }

    @Test
    void invalidateDocumentDropsOnlyDocumentBackedEntries() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey forDoc = key("q-doc", RagQueryScope.EMPTY);
        RagCacheKey forMixed = key("q-mix", RagQueryScope.EMPTY);
        cache.store(forDoc, answer("doc-ans"), new CacheCoverage(Set.of(), Set.of("doc_a")));
        cache.store(forMixed, answer("mix-ans"), new CacheCoverage(Set.of("mtg_a"), Set.of("doc_a")));

        int dropped = cache.invalidateDocument("tenant_01", "doc_a");

        assertThat(dropped).isEqualTo(2);
        assertThat(cache.lookup(forDoc)).isEmpty();
        assertThat(cache.lookup(forMixed)).isEmpty();
    }

    @Test
    void invalidateUnknownOwnerIsZeroDrop() {
        InMemoryRagAnswerCache cache = newCache();
        cache.store(key("q", RagQueryScope.EMPTY), answer("a"),
            new CacheCoverage(Set.of("mtg_a"), Set.of()));

        assertThat(cache.invalidateMeeting("tenant_01", "mtg_missing")).isEqualTo(0);
        assertThat(cache.invalidateDocument("tenant_01", "doc_missing")).isEqualTo(0);
    }

    @Test
    void invalidateTenantDropsEverythingForThatTenant() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey tenant1 = new RagCacheKey(
            "tenant_01", "u", "q", RagQueryScope.EMPTY, 5, false
        );
        RagCacheKey tenant2 = new RagCacheKey(
            "tenant_02", "u", "q", RagQueryScope.EMPTY, 5, false
        );
        cache.store(tenant1, answer("1"), new CacheCoverage(Set.of("m"), Set.of()));
        cache.store(tenant2, answer("2"), new CacheCoverage(Set.of("m"), Set.of()));

        int dropped = cache.invalidateTenant("tenant_01");

        assertThat(dropped).isEqualTo(1);
        assertThat(cache.lookup(tenant1)).isEmpty();
        assertThat(cache.lookup(tenant2)).isPresent();
    }

    @Test
    void expiredEntriesEvictedLazilyOnLookup() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-05-16T00:00:00Z"));
        Clock clock = new MutableClock(nowRef);
        InMemoryRagAnswerCache cache = new InMemoryRagAnswerCache(clock, 60L, 1024);

        RagCacheKey k = key("q", RagQueryScope.EMPTY);
        cache.store(k, answer("a"), new CacheCoverage(Set.of("mtg_a"), Set.of()));
        nowRef.set(nowRef.get().plus(Duration.ofSeconds(61)));

        assertThat(cache.lookup(k)).isEmpty();
        // Lazy eviction should also clear the meeting index.
        assertThat(cache.snapshotMeetingIndex("tenant_01", "mtg_a")).isEmpty();
    }

    @Test
    void overflowingMaxEntriesDropsOldestFirst() {
        AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-05-16T00:00:00Z"));
        Clock clock = new MutableClock(nowRef);
        InMemoryRagAnswerCache cache = new InMemoryRagAnswerCache(clock, 3600L, 2);

        RagCacheKey k1 = key("q1", RagQueryScope.EMPTY);
        RagCacheKey k2 = key("q2", RagQueryScope.EMPTY);
        RagCacheKey k3 = key("q3", RagQueryScope.EMPTY);
        cache.store(k1, answer("1"), new CacheCoverage(Set.of(), Set.of()));
        nowRef.set(nowRef.get().plusSeconds(1));
        cache.store(k2, answer("2"), new CacheCoverage(Set.of(), Set.of()));
        nowRef.set(nowRef.get().plusSeconds(1));
        cache.store(k3, answer("3"), new CacheCoverage(Set.of(), Set.of()));

        assertThat(cache.size()).isLessThanOrEqualTo(2);
        // k1 is the oldest — should be evicted first.
        assertThat(cache.lookup(k1)).isEmpty();
        assertThat(cache.lookup(k3)).isPresent();
    }

    @Test
    void onChunkReindexInvalidatesByMeetingId() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey k = key("q", RagQueryScope.EMPTY);
        cache.store(k, answer("a"), new CacheCoverage(Set.of("mtg_x"), Set.of()));

        cache.onChunkReindex(new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", "mtg_x", null, List.of(),
            "v1", 1, null, null
        ));

        assertThat(cache.lookup(k)).isEmpty();
    }

    @Test
    void onChunkReindexInvalidatesByDocumentId() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey k = key("q", RagQueryScope.EMPTY);
        cache.store(k, answer("a"), new CacheCoverage(Set.of(), Set.of("doc_x")));

        cache.onChunkReindex(new KnowledgeChunkReindexRequestedEvent(
            "tenant_01", null, "doc_x", List.of(),
            "v1", null, null, null
        ));

        assertThat(cache.lookup(k)).isEmpty();
    }

    @Test
    void clearWipesEverything() {
        InMemoryRagAnswerCache cache = newCache();
        cache.store(key("q", RagQueryScope.EMPTY), answer("a"),
            new CacheCoverage(Set.of("mtg_a"), Set.of("doc_a")));

        cache.clear();

        assertThat(cache.size()).isEqualTo(0);
        assertThat(cache.snapshotMeetingIndex("tenant_01", "mtg_a")).isEmpty();
    }

    @Test
    void overwriteUpdatesCoverageIndex() {
        InMemoryRagAnswerCache cache = newCache();
        RagCacheKey k = key("q", RagQueryScope.EMPTY);
        cache.store(k, answer("first"), new CacheCoverage(Set.of("mtg_old"), Set.of()));
        cache.store(k, answer("second"), new CacheCoverage(Set.of("mtg_new"), Set.of()));

        // Old meeting index should no longer point at this key.
        assertThat(cache.snapshotMeetingIndex("tenant_01", "mtg_old")).isEmpty();
        assertThat(cache.snapshotMeetingIndex("tenant_01", "mtg_new")).containsExactly(k);
        assertThat(cache.lookup(k).orElseThrow().answer()).isEqualTo("second");
    }

    @Test
    void cacheKeyRejectsBlankIdentity() {
        assertThatThrownBy(() -> new RagCacheKey(
            "", "u", "q", RagQueryScope.EMPTY, 5, false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagCacheKey(
            "t", "", "q", RagQueryScope.EMPTY, 5, false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagCacheKey(
            "t", "u", null, RagQueryScope.EMPTY, 5, false
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagCacheKey(
            "t", "u", "  ", RagQueryScope.EMPTY, 5, false
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static InMemoryRagAnswerCache newCache() {
        return new InMemoryRagAnswerCache(Clock.systemUTC(), 300L, 1024);
    }

    private static RagCacheKey key(String question, RagQueryScope scope) {
        return new RagCacheKey(
            "tenant_01", "user_01", question, scope, 5, false
        );
    }

    private static RagAnswerDTO answer(String body) {
        return new RagAnswerDTO(body, List.of(), RagAnswerCoverage.TRANSCRIPT_ONLY, "llmlog_" + body);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(AtomicReference<Instant> now) { this.now = now; }
        @Override public Instant instant() { return now.get(); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
