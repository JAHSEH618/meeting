package com.meeting.api.domain.auth;

import java.util.Optional;

public interface RefreshTokenRepository {
    void save(RefreshToken token);
    Optional<RefreshToken> findByTokenId(String tokenId);
    void revokeByTokenId(String tokenId);
    void revokeAllByUserId(String userId);
}
