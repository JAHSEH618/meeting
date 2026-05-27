package com.meeting.api.app.auth;

import com.meeting.api.client.auth.AuthFacade;
import com.meeting.api.client.auth.AuthUserDTO;
import com.meeting.api.client.auth.LoginCommand;
import com.meeting.api.client.auth.LoginResultDTO;
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
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryAuthApplicationService(
        @Value("${meeting.admin-jwt.secret:" + AdminJwtCodec.DEFAULT_SECRET + "}") String adminJwtSecret,
        @Value("${meeting.admin-jwt.audience:" + AdminJwtCodec.DEFAULT_AUDIENCE + "}") String adminJwtAudience,
        @Value("${meeting.admin-jwt.issuer:" + AdminJwtCodec.DEFAULT_ISSUER + "}") String adminJwtIssuer
    ) {
        this(Clock.systemUTC(), new AdminJwtCodec(adminJwtSecret, adminJwtAudience, adminJwtIssuer));
    }

    public InMemoryAuthApplicationService() {
        this(Clock.systemUTC(), AdminJwtCodec.defaults());
    }

    public InMemoryAuthApplicationService(Clock clock) {
        this(clock, AdminJwtCodec.defaults());
    }

    InMemoryAuthApplicationService(Clock clock, AdminJwtCodec jwtCodec) {
        this.clock = clock;
        this.jwtCodec = jwtCodec;
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
        return new LoginResultDTO(token, expiresAt, user);
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
    public void logout(String accessToken) {
        if (accessToken == null) {
            return;
        }
        sessions.remove(accessToken.startsWith("Bearer ") ? accessToken.substring("Bearer ".length()) : accessToken);
    }

    private record Session(AuthUserDTO user, OffsetDateTime expiresAt) {}
}
