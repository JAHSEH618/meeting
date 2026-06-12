package com.meeting.api.domain.auth;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class RefreshTokenTest {
    @Test
    void isExpired_returnsTrueWhenExpired() {
        OffsetDateTime past = OffsetDateTime.now().minusHours(1);
        RefreshToken token = new RefreshToken("token_123", "user_1", "tenant_1", past);
        assertThat(token.isExpired(OffsetDateTime.now())).isTrue();
    }

    @Test
    void isExpired_returnsFalseWhenNotExpired() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        RefreshToken token = new RefreshToken("token_123", "user_1", "tenant_1", future);
        assertThat(token.isExpired(OffsetDateTime.now())).isFalse();
    }

    @Test
    void isExpired_returnsTrueWhenExactlyAtExpiryInstant() {
        OffsetDateTime now = OffsetDateTime.now();
        RefreshToken token = new RefreshToken("token_123", "user_1", "tenant_1", now);
        assertThat(token.isExpired(now)).isTrue();
    }

    @Test
    void constructor_throwsExceptionWhenTokenIdIsNull() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        assertThatThrownBy(() -> new RefreshToken(null, "user_1", "tenant_1", future))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tokenId");
    }

    @Test
    void constructor_throwsExceptionWhenTokenIdIsBlank() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        assertThatThrownBy(() -> new RefreshToken("  ", "user_1", "tenant_1", future))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tokenId");
    }

    @Test
    void constructor_throwsExceptionWhenUserIdIsNull() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        assertThatThrownBy(() -> new RefreshToken("token_123", null, "tenant_1", future))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("userId");
    }

    @Test
    void constructor_throwsExceptionWhenUserIdIsBlank() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        assertThatThrownBy(() -> new RefreshToken("token_123", "  ", "tenant_1", future))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("userId");
    }

    @Test
    void constructor_throwsExceptionWhenTenantIdIsNull() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        assertThatThrownBy(() -> new RefreshToken("token_123", "user_1", null, future))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId");
    }

    @Test
    void constructor_throwsExceptionWhenTenantIdIsBlank() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        assertThatThrownBy(() -> new RefreshToken("token_123", "user_1", "  ", future))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId");
    }

    @Test
    void constructor_throwsExceptionWhenExpiresAtIsNull() {
        assertThatThrownBy(() -> new RefreshToken("token_123", "user_1", "tenant_1", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("expiresAt");
    }

    @Test
    void isExpired_throwsExceptionWhenNowIsNull() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        RefreshToken token = new RefreshToken("token_123", "user_1", "tenant_1", future);
        assertThatThrownBy(() -> token.isExpired(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("now");
    }
}
