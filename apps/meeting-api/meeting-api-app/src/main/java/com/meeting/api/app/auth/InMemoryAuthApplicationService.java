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
import org.springframework.stereotype.Service;

@Service
public class InMemoryAuthApplicationService implements AuthFacade {
    private final Clock clock;
    private final Map<String, AuthUserDTO> sessions = new ConcurrentHashMap<>();

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
        sessions.put(token, user);
        return new LoginResultDTO(token, OffsetDateTime.now(clock).plusHours(8), user);
    }

    @Override
    public Optional<AuthUserDTO> authenticate(String accessToken) {
        return Optional.ofNullable(accessToken)
            .map(token -> token.startsWith("Bearer ") ? token.substring("Bearer ".length()) : token)
            .flatMap(token -> Optional.ofNullable(sessions.get(token)));
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
}
