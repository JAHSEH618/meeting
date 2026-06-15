package com.meeting.api.app.auth;

import com.meeting.api.client.auth.AuthFacade;
import com.meeting.api.client.auth.AuthUserDTO;
import com.meeting.api.client.auth.LoginCommand;
import com.meeting.api.client.auth.LoginResultDTO;
import com.meeting.api.client.auth.RefreshResultDTO;
import com.meeting.api.domain.auth.RefreshToken;
import com.meeting.api.domain.auth.RefreshTokenRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Dev-only auth implementation. {@code @Profile("!prod")} keeps the
 * bean out of the prod context so the hardcoded {@code admin/admin123 →
 * tenant_default} credentials cannot accidentally service real traffic.
 * {@link com.meeting.api.start.config.ProdProfileValidator} adds a
 * second gate: it fails-fast at boot if {@code meeting.auth.mode}
 * is still {@code in-memory}.
 *
 * <p>Tokens are stored in a {@link ConcurrentHashMap} together with
 * their {@code expiresAt}; {@link #authenticate(String)} now enforces
 * the TTL on every lookup so an old session token cannot resurrect a
 * dropped login.
 */
@Service
@Profile("!prod")
public class InMemoryAuthApplicationService implements AuthFacade {

    private static final java.time.Duration TOKEN_TTL = java.time.Duration.ofHours(8);

    private final Clock clock;
    private final AdminJwtCodec jwtCodec;
    private final RefreshTokenRepository refreshTokenRepository;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryAuthApplicationService(
        @Value("${meeting.admin-jwt.secret:" + AdminJwtCodec.DEFAULT_SECRET + "}") String adminJwtSecret,
        @Value("${meeting.admin-jwt.audience:" + AdminJwtCodec.DEFAULT_AUDIENCE + "}") String adminJwtAudience,
        @Value("${meeting.admin-jwt.issuer:" + AdminJwtCodec.DEFAULT_ISSUER + "}") String adminJwtIssuer,
        RefreshTokenRepository refreshTokenRepository
    ) {
        this(Clock.systemUTC(), new AdminJwtCodec(adminJwtSecret, adminJwtAudience, adminJwtIssuer), refreshTokenRepository);
    }

    public InMemoryAuthApplicationService() {
        this(Clock.systemUTC(), AdminJwtCodec.defaults(), new InMemoryRefreshTokenRepository());
    }

    public InMemoryAuthApplicationService(Clock clock) {
        this(clock, AdminJwtCodec.defaults(), new InMemoryRefreshTokenRepository());
    }

    InMemoryAuthApplicationService(Clock clock, AdminJwtCodec jwtCodec, RefreshTokenRepository refreshTokenRepository) {
        this.clock = clock;
        this.jwtCodec = jwtCodec;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    /**
     * Simple in-memory implementation for tests.
     */
    private static class InMemoryRefreshTokenRepository implements RefreshTokenRepository {
        private final Map<String, RefreshToken> tokens = new ConcurrentHashMap<>();

        @Override
        public void save(RefreshToken token) {
            tokens.put(token.tokenId(), token);
        }

        @Override
        public Optional<RefreshToken> findByTokenId(String tokenId) {
            return Optional.ofNullable(tokens.get(tokenId));
        }

        @Override
        public void revokeByTokenId(String tokenId) {
            tokens.remove(tokenId);
        }

        @Override
        public void revokeAllByUserId(String userId) {
            tokens.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
        }
    }

    @Override
    public LoginResultDTO login(LoginCommand command) {
        if (!"admin".equals(command.username()) || !"admin123".equals(command.password())) {
            throw new IllegalArgumentException("invalid username or password");
        }
        AuthUserDTO user = new AuthUserDTO(
            "user_admin",
            "tenant_default",
            "person_admin",
            "Admin",
            List.of("ADMIN"),
            List.of("meeting:create", "meeting:read", "task:create", "task:read", "task:retry", "task:cancel")
        );
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(TOKEN_TTL);
        String token = jwtCodec.encode(user, expiresAt);
        sessions.put(token, new Session(user, expiresAt));

        // Issue refresh token (30 days)
        String refreshTokenId = "rt_" + java.util.UUID.randomUUID();
        OffsetDateTime refreshExpiresAt = OffsetDateTime.now(clock).plusDays(30);
        RefreshToken refreshToken = new RefreshToken(refreshTokenId, user.userId(), user.tenantId(), refreshExpiresAt);
        refreshTokenRepository.save(refreshToken);

        // Return result with access token only (refresh token goes in HttpOnly cookie)
        return new LoginResultDTO(token, expiresAt, user, refreshTokenId);
    }

    @Override
    public Optional<AuthUserDTO> authenticate(String accessToken) {
        if (accessToken == null) return Optional.empty();
        String raw = accessToken.startsWith("Bearer ")
            ? accessToken.substring("Bearer ".length()) : accessToken;
        Session session = sessions.get(raw);
        if (session != null) {
            if (!session.expiresAt.isAfter(OffsetDateTime.now(clock))) {
                sessions.remove(raw);
                return Optional.empty();
            }
            return Optional.of(session.user);
        }
        return jwtCodec.decode(raw, clock);
    }

    @Override
    public Optional<AuthUserDTO> me(String accessToken) {
        return authenticate(accessToken);
    }

    @Override
    public RefreshResultDTO refresh(String refreshTokenId, String csrfToken) {
        if (refreshTokenId == null || refreshTokenId.isBlank()) {
            throw new IllegalArgumentException("refresh token required");
        }

        // CSRF validation (simple match for now; full double-submit in controller)
        if (csrfToken == null || csrfToken.isBlank()) {
            throw new IllegalArgumentException("CSRF token required");
        }

        // Find refresh token
        Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByTokenId(refreshTokenId);
        if (tokenOpt.isEmpty()) {
            throw new IllegalArgumentException("invalid refresh token");
        }

        RefreshToken refreshToken = tokenOpt.get();

        // Check expiry
        if (refreshToken.isExpired(OffsetDateTime.now(clock))) {
            refreshTokenRepository.revokeByTokenId(refreshTokenId);
            throw new IllegalArgumentException("refresh token expired");
        }

        // Issue new access token
        AuthUserDTO user = new AuthUserDTO(
            refreshToken.userId(),
            refreshToken.tenantId(),
            "person_admin",  // Simplified for MVP
            "Admin",
            List.of("ADMIN"),
            List.of("meeting:create", "meeting:read")
        );

        OffsetDateTime accessExpiresAt = OffsetDateTime.now(clock).plus(TOKEN_TTL);
        String accessToken = jwtCodec.encode(user, accessExpiresAt);

        return new RefreshResultDTO(accessToken, accessExpiresAt);
    }

    @Override
    public void logout(String accessToken) {
        if (accessToken == null) {
            return;
        }
        String raw = accessToken.startsWith("Bearer ")
            ? accessToken.substring("Bearer ".length())
            : accessToken;

        sessions.remove(raw);

        // Decode to get userId, then revoke all refresh tokens
        Optional<AuthUserDTO> userOpt = jwtCodec.decode(raw, clock);
        userOpt.ifPresent(user -> refreshTokenRepository.revokeAllByUserId(user.userId()));
    }

    @Override
    public void logoutRefreshToken(String refreshTokenId) {
        if (refreshTokenId == null || refreshTokenId.isBlank()) {
            return;
        }
        refreshTokenRepository.revokeByTokenId(refreshTokenId);
    }

    private record Session(AuthUserDTO user, OffsetDateTime expiresAt) {}
}
