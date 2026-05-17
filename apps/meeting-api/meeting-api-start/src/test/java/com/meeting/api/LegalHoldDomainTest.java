package com.meeting.api;

import com.meeting.api.client.enums.LegalHoldScopeType;
import com.meeting.api.client.enums.LegalHoldStatus;
import com.meeting.api.domain.compliance.LegalHold;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalHoldDomainTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T03:00:00Z");

    @Test
    void freshHoldIsActive() {
        LegalHold hold = sampleHold().build();
        assertThat(hold.status()).isEqualTo(LegalHoldStatus.ACTIVE);
        assertThat(hold.isActive()).isTrue();
        assertThat(hold.releasedAt()).isNull();
    }

    @Test
    void releasingTransitionsToReleasedAndStampsAuditFields() {
        LegalHold hold = sampleHold().build();
        hold.release("user_admin", "case closed", NOW.plusDays(1));
        assertThat(hold.status()).isEqualTo(LegalHoldStatus.RELEASED);
        assertThat(hold.isActive()).isFalse();
        assertThat(hold.releasedBy()).isEqualTo("user_admin");
        assertThat(hold.releaseReason()).isEqualTo("case closed");
        assertThat(hold.releasedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void cannotReleaseTwice() {
        LegalHold hold = sampleHold().build();
        hold.release("user_admin", "reason 1", NOW);
        assertThatThrownBy(() -> hold.release("user_other", "reason 2", NOW.plusHours(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already in status RELEASED");
    }

    @Test
    void rejectsBlankReason() {
        assertThatThrownBy(() -> sampleHold().reason(" ").build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reason");
    }

    @Test
    void rejectsBlankScopeId() {
        assertThatThrownBy(() -> sampleHold().scopeId("").build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scopeId");
    }

    @Test
    void releaseRejectsBlankReleaseReason() {
        LegalHold hold = sampleHold().build();
        assertThatThrownBy(() -> hold.release("user_admin", " ", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("releaseReason");
    }

    private LegalHold.Builder sampleHold() {
        return LegalHold.builder()
            .id("lh_test_01")
            .tenantId("tenant_test_01")
            .scopeType(LegalHoldScopeType.MEETING)
            .scopeId("mtg_test_01")
            .reason("regulator inquiry")
            .requestedBy("user_compliance")
            .createdAt(NOW);
    }
}
