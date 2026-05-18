package com.meeting.api;

import com.meeting.api.app.breakglass.BreakGlassExpiryScanner;
import com.meeting.api.app.common.TenantScopedTransaction;
import com.meeting.api.client.common.PageResult;
import com.meeting.api.client.enums.BreakGlassStatus;
import com.meeting.api.domain.breakglass.BreakGlassRequest;
import com.meeting.api.domain.breakglass.BreakGlassRequestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreakGlassExpiryScannerTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T08:00:00Z");
    private static final OffsetDateTime APPROVED_AT = NOW.minusHours(5);

    @Test
    void expiresApprovedRequestsPastValidUntil() {
        InMemoryRepo repo = new InMemoryRepo();
        BreakGlassRequest expired = approvedRequest("bg_expired", "tenant_01", APPROVED_AT, Duration.ofHours(4));
        BreakGlassRequest stillActive = approvedRequest("bg_active", "tenant_01", NOW.minusMinutes(30), Duration.ofHours(4));
        repo.rows.put(expired.id(), expired);
        repo.rows.put(stillActive.id(), stillActive);

        BreakGlassExpiryScanner scanner = new BreakGlassExpiryScanner(
            repo, TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), 50
        );

        BreakGlassExpiryScanner.ScanReport report = scanner.scanOnce(List.of("tenant_01"));

        assertThat(report.claimed()).isEqualTo(1);
        assertThat(report.expired()).isEqualTo(1);
        assertThat(repo.rows.get("bg_expired").status()).isEqualTo(BreakGlassStatus.EXPIRED);
        assertThat(repo.rows.get("bg_active").status()).isEqualTo(BreakGlassStatus.APPROVED);
    }

    @Test
    void skipsRowsThatBecameInactiveBetweenClaimAndCheck() {
        // Simulate the race-condition guard: claimExpired returns a row,
        // but the in-memory expire-at gets re-extended (e.g. by another
        // approve flow). Scanner must NOT call expire().
        InMemoryRepo repo = new InMemoryRepo();
        BreakGlassRequest row = approvedRequest("bg_race", "tenant_01", NOW.minusHours(5), Duration.ofHours(4));
        repo.rows.put(row.id(), row);
        // Custom override: claimExpired returns it, but immediately bump
        // its validUntil past now.
        repo.afterClaim = r -> {
            try {
                java.lang.reflect.Field f = BreakGlassRequest.class.getDeclaredField("validUntil");
                f.setAccessible(true);
                f.set(r, NOW.plusHours(1));
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        };

        BreakGlassExpiryScanner scanner = new BreakGlassExpiryScanner(
            repo, TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), 50
        );

        BreakGlassExpiryScanner.ScanReport report = scanner.scanOnce(List.of("tenant_01"));

        assertThat(report.claimed()).isEqualTo(1);
        assertThat(report.expired()).isEqualTo(0);
        assertThat(repo.rows.get("bg_race").status()).isEqualTo(BreakGlassStatus.APPROVED);
    }

    @Test
    void scansMultipleTenantsAndAggregatesCounts() {
        InMemoryRepo repo = new InMemoryRepo();
        repo.rows.put("bg_t1", approvedRequest("bg_t1", "tenant_01", APPROVED_AT, Duration.ofHours(4)));
        repo.rows.put("bg_t2", approvedRequest("bg_t2", "tenant_02", APPROVED_AT, Duration.ofHours(4)));

        BreakGlassExpiryScanner scanner = new BreakGlassExpiryScanner(
            repo, TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), 50
        );

        BreakGlassExpiryScanner.ScanReport report = scanner.scanOnce(List.of("tenant_01", "tenant_02"));
        assertThat(report.claimed()).isEqualTo(2);
        assertThat(report.expired()).isEqualTo(2);
    }

    @Test
    void emptyTenantListYieldsZero() {
        InMemoryRepo repo = new InMemoryRepo();
        BreakGlassExpiryScanner scanner = new BreakGlassExpiryScanner(
            repo, TenantScopedTransaction.immediate(),
            Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), 50
        );
        BreakGlassExpiryScanner.ScanReport report = scanner.scanOnce(List.of());
        assertThat(report.claimed()).isZero();
        assertThat(report.expired()).isZero();
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        InMemoryRepo repo = new InMemoryRepo();
        assertThatThrownBy(() -> new BreakGlassExpiryScanner(
            repo, TenantScopedTransaction.immediate(), Clock.systemUTC(), 0
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static BreakGlassRequest approvedRequest(
        String id, String tenantId, OffsetDateTime approvedAt, Duration window
    ) {
        BreakGlassRequest req = BreakGlassRequest.builder()
            .id(id)
            .tenantId(tenantId)
            .requesterId("user_requester")
            .scopeType("MEETING")
            .scopeId("mtg_" + id)
            .reason("test")
            .createdAt(approvedAt.minusMinutes(5))
            .build();
        req.approve("user_admin", approvedAt, window);
        return req;
    }

    /** Hand-rolled in-memory repo so the scanner test runs free of Spring + JDBC. */
    private static class InMemoryRepo implements BreakGlassRequestRepository {
        final Map<String, BreakGlassRequest> rows = new HashMap<>();
        java.util.function.Consumer<BreakGlassRequest> afterClaim = r -> {};

        @Override public void save(BreakGlassRequest req) { rows.put(req.id(), req); }
        @Override public void update(BreakGlassRequest req) { rows.put(req.id(), req); }

        @Override
        public Optional<BreakGlassRequest> findById(String tenantId, String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public PageResult<BreakGlassRequest> listByTenant(
            String tenantId, BreakGlassStatus status, String cursor, int limit
        ) {
            return new PageResult<>(new ArrayList<>(rows.values()), new PageResult.PageInfo(null, false, limit));
        }

        @Override
        public List<BreakGlassRequest> claimExpired(String tenantId, int limit) {
            List<BreakGlassRequest> hits = new ArrayList<>();
            for (BreakGlassRequest req : rows.values()) {
                if (!tenantId.equals(req.tenantId())) continue;
                if (req.status() != BreakGlassStatus.APPROVED) continue;
                if (req.validUntil() == null || !req.validUntil().isBefore(NOW)) continue;
                hits.add(req);
                afterClaim.accept(req);
                if (hits.size() >= limit) break;
            }
            return hits;
        }
    }
}
