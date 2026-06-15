package com.meeting.api.client.auth;

import java.util.Optional;

public interface AuthFacade {
    LoginResultDTO login(LoginCommand command);

    Optional<AuthUserDTO> authenticate(String accessToken);

    Optional<AuthUserDTO> me(String accessToken);

    void logout(String accessToken);

    void logoutRefreshToken(String refreshTokenId);

    /**
     * Refresh access token using refresh token. Returns new access token and expiry.
     */
    RefreshResultDTO refresh(String refreshTokenId, String csrfToken);
}
