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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public InMemoryAuthApplicationService() {
        this(Clock.systemUTC());
    }

    public InMemoryAuthApplicationService(Clock clock) {
        this.clock = clock;
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
            List.of("admin"),
            List.of("meeting:create", "meeting:read", "task:create", "task:read", "task:retry", "task:cancel")
        );
        String token = "mvp0_" + UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(TOKEN_TTL);
        sessions.put(token, new Session(user, expiresAt));
        return new LoginResultDTO(token, expiresAt, user);
    }

    @Override
    public Optional<AuthUserDTO> authenticate(String accessToken) {
        if (accessToken == null) return Optional.empty();
        String raw = accessToken.startsWith("Bearer ")
            ? accessToken.substring("Bearer ".length()) : accessToken;
        Session session = sessions.get(raw);
        if (session == null) return Optional.empty();
        if (!session.expiresAt.isAfter(OffsetDateTime.now(clock))) {
            sessions.remove(raw);
            return Optional.empty();
        }
        return Optional.of(session.user);
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
