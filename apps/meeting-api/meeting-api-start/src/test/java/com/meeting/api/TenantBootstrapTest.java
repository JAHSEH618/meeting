package com.meeting.api;

import com.meeting.api.start.config.TenantBootstrap;
import com.meeting.api.start.config.TenantBootstrap.Mode;
import com.meeting.api.start.config.TenantRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantBootstrapTest {

    @Test
    void noConfiguredTenantsIsANoOp() {
        FakeRegistry registry = new FakeRegistry(Set.of());
        new TenantBootstrap(registry).run(List.of(), Mode.VALIDATE_ONLY);
        new TenantBootstrap(registry).run(List.of(), Mode.SEED_MISSING);
        assertThat(registry.seeded).isEmpty();
    }

    @Test
    void seedMissingInsertsTenantsThatDoNotExist() {
        FakeRegistry registry = new FakeRegistry(Set.of("tenant_acme"));
        new TenantBootstrap(registry).run(
            List.of("tenant_acme", "tenant_default"), Mode.SEED_MISSING);
        assertThat(registry.seeded).containsExactly("tenant_default");
    }

    @Test
    void seedMissingIsIdempotentWhenAllPresent() {
        FakeRegistry registry = new FakeRegistry(Set.of("tenant_acme", "tenant_default"));
        new TenantBootstrap(registry).run(
            List.of("tenant_acme", "tenant_default"), Mode.SEED_MISSING);
        assertThat(registry.seeded).isEmpty();
    }

    @Test
    void validateOnlyPassesWhenAllPresent() {
        FakeRegistry registry = new FakeRegistry(Set.of("tenant_acme", "tenant_emea"));
        new TenantBootstrap(registry).run(
            List.of("tenant_acme", "tenant_emea"), Mode.VALIDATE_ONLY);
        assertThat(registry.seeded).isEmpty();
    }

    @Test
    void validateOnlyFailsFastWhenAnyMissing() {
        FakeRegistry registry = new FakeRegistry(Set.of("tenant_acme"));
        assertThatThrownBy(() -> new TenantBootstrap(registry).run(
            List.of("tenant_acme", "tenant_default"), Mode.VALIDATE_ONLY))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("tenant_default");
        assertThat(registry.seeded).isEmpty();
    }

    private static final class FakeRegistry implements TenantRegistry {
        private final Set<String> existing;
        final List<String> seeded = new ArrayList<>();

        FakeRegistry(Set<String> existing) {
            this.existing = new LinkedHashSet<>(existing);
        }

        @Override
        public Set<String> findExisting(Collection<String> tenantIds) {
            Set<String> result = new LinkedHashSet<>(tenantIds);
            result.retainAll(existing);
            return result;
        }

        @Override
        public void seed(String tenantId, String displayName) {
            seeded.add(tenantId);
            existing.add(tenantId);
        }
    }
}
