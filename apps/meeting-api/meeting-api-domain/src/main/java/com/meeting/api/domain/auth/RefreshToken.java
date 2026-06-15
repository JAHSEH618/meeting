package com.meeting.api.domain.auth;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Domain model for refresh tokens.
 *
 * <p>Refresh tokens are stored server-side and referenced by tokenId in HttpOnly cookies.
 * They are never exposed in API responses and are used to issue new access tokens without
 * requiring re-authentication.</p>
 *
 * <p>Lifecycle: issued at login, rotated on refresh, revoked on logout or expiry.</p>
 */
public record RefreshToken(
    String tokenId,
    String userId,
    String tenantId,
    OffsetDateTime expiresAt
) {
    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if any field is null or blank (for String fields)
     */
    public RefreshToken {
        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("tokenId must not be null or blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be null or blank");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be null or blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /**
     * Checks if this token has expired at the given instant.
     *
     * <p>A token is considered expired if the expiry instant is not after the given instant.
     * This means a token expires at the exact expiry instant (inclusive boundary).</p>
     *
     * @param now the instant to check against, must not be null
     * @return true if expired (expiresAt <= now), false otherwise
     * @throws IllegalArgumentException if now is null
     */
    public boolean isExpired(OffsetDateTime now) {
        Objects.requireNonNull(now, "now must not be null");
        return !expiresAt.isAfter(now);
    }
}
