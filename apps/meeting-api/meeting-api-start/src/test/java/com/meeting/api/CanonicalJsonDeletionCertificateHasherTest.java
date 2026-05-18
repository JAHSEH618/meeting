package com.meeting.api;

import com.meeting.api.domain.compliance.DeletionExecutorPort.DeletionOutcome;
import com.meeting.api.infrastructure.gateway.compliance.CanonicalJsonDeletionCertificateHasher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonDeletionCertificateHasherTest {

    private final CanonicalJsonDeletionCertificateHasher hasher = new CanonicalJsonDeletionCertificateHasher();

    @Test
    void sameInputsProduceSameHash() {
        DeletionOutcome outcome = new DeletionOutcome(
            Map.of("meetings", 1), Map.of(), Map.of(), List.of()
        );
        String a = hasher.compute("tenant_01", "dj_01", outcome);
        String b = hasher.compute("tenant_01", "dj_01", outcome);
        assertThat(a).isEqualTo(b);
        assertThat(a).startsWith("sha256:");
    }

    @Test
    void differentTenantIdYieldsDifferentHash() {
        DeletionOutcome outcome = new DeletionOutcome(
            Map.of("meetings", 1), Map.of(), Map.of(), List.of()
        );
        String a = hasher.compute("tenant_01", "dj_01", outcome);
        String b = hasher.compute("tenant_02", "dj_01", outcome);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void mapEntryOrderingDoesNotAffectHash() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("meetings", 1);
        ordered.put("documents", 2);
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("documents", 2);
        reversed.put("meetings", 1);

        String a = hasher.compute("tenant_01", "dj_01",
            new DeletionOutcome(ordered, Map.of(), Map.of(), List.of()));
        String b = hasher.compute("tenant_01", "dj_01",
            new DeletionOutcome(reversed, Map.of(), Map.of(), List.of()));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void differentFailedItemsYieldsDifferentHash() {
        DeletionOutcome ok = new DeletionOutcome(
            Map.of("meetings", 1), Map.of(), Map.of(), List.of()
        );
        DeletionOutcome partial = new DeletionOutcome(
            Map.of("meetings", 1), Map.of(), Map.of(), List.of("file:x:not_found")
        );
        assertThat(hasher.compute("tenant_01", "dj_01", ok))
            .isNotEqualTo(hasher.compute("tenant_01", "dj_01", partial));
    }
}
