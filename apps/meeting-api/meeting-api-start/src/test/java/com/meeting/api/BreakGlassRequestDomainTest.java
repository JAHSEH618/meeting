package com.meeting.api;

import com.meeting.api.client.enums.BreakGlassStatus;
import com.meeting.api.domain.breakglass.BreakGlassRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BreakGlassRequestDomainTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-05-18T05:00:00Z");

    @Test
    void freshRequestIsPending() {
        BreakGlassRequest req = sample().build();
        assertThat(req.status()).isEqualTo(BreakGlassStatus.PENDING);
        assertThat(req.isActiveAt(NOW)).isFalse();
        assertThat(req.validFrom()).isNull();
        assertThat(req.validUntil()).isNull();
    }

    @Test
    void approveTransitionsAndStampsWindow() {
        BreakGlassRequest req = sample().build();
        req.approve("user_admin", NOW, Duration.ofHours(4));
        assertThat(req.status()).isEqualTo(BreakGlassStatus.APPROVED);
        assertThat(req.approverId()).isEqualTo("user_admin");
        assertThat(req.validFrom()).isEqualTo(NOW);
        assertThat(req.validUntil()).isEqualTo(NOW.plusHours(4));
        assertThat(req.isActiveAt(NOW.plusHours(1))).isTrue();
        assertThat(req.isActiveAt(NOW.plusHours(5))).isFalse();
    }

    @Test
    void approveDefaultWindowFourHours() {
        BreakGlassRequest req = sample().build();
        req.approve("user_admin", NOW, null);
        assertThat(req.validUntil()).isEqualTo(NOW.plusHours(4));
    }

    @Test
    void selfApprovalRejected() {
        BreakGlassRequest req = sample().requesterId("user_self").build();
        assertThatThrownBy(() -> req.approve("user_self", NOW, Duration.ofHours(1)))
            .isInstanceOf(BreakGlassRequest.SelfApprovalForbiddenException.class);
        assertThat(req.status()).isEqualTo(BreakGlassStatus.PENDING);
    }

    @Test
    void cannotApproveTwice() {
        BreakGlassRequest req = sample().build();
        req.approve("user_admin", NOW, Duration.ofHours(4));
        assertThatThrownBy(() -> req.approve("user_admin_2", NOW.plusMinutes(1), Duration.ofHours(4)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectTransitionsWithReason() {
        BreakGlassRequest req = sample().build();
        req.reject("user_admin", "no business justification", NOW);
        assertThat(req.status()).isEqualTo(BreakGlassStatus.REJECTED);
        assertThat(req.rejectReason()).isEqualTo("no business justification");
        assertThat(req.rejectedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectBlankReasonInvalid() {
        BreakGlassRequest req = sample().build();
        assertThatThrownBy(() -> req.reject("user_admin", " ", NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokeApprovedTransitions() {
        BreakGlassRequest req = sample().build();
        req.approve("user_admin", NOW, Duration.ofHours(4));
        OffsetDateTime later = NOW.plusMinutes(30);
        req.revoke("user_security", later);
        assertThat(req.status()).isEqualTo(BreakGlassStatus.REVOKED);
        assertThat(req.revokedBy()).isEqualTo("user_security");
    }

    @Test
    void revokePendingRejected() {
        BreakGlassRequest req = sample().build();
        assertThatThrownBy(() -> req.revoke("user_security", NOW))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expireMovesApprovedToExpired() {
        BreakGlassRequest req = sample().build();
        req.approve("user_admin", NOW, Duration.ofHours(4));
        req.expire(NOW.plusHours(5));
        assertThat(req.status()).isEqualTo(BreakGlassStatus.EXPIRED);
    }

    @Test
    void expireIsIdempotent() {
        BreakGlassRequest req = sample().build();
        req.expire(NOW);    // still PENDING — no-op
        assertThat(req.status()).isEqualTo(BreakGlassStatus.PENDING);

        req.approve("user_admin", NOW, Duration.ofHours(4));
        req.expire(NOW.plusHours(5));
        req.expire(NOW.plusHours(6));    // already EXPIRED — no-op
        assertThat(req.status()).isEqualTo(BreakGlassStatus.EXPIRED);
    }

    private BreakGlassRequest.Builder sample() {
        return BreakGlassRequest.builder()
            .id("bg_test_01")
            .tenantId("tenant_test_01")
            .requesterId("user_requester")
            .scopeType("MEETING")
            .scopeId("mtg_test_01")
            .reason("incident response")
            .createdAt(NOW);
    }
}
