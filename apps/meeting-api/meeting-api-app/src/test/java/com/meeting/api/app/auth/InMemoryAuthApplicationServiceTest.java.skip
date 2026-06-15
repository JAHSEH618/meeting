package com.meeting.api.app.auth;

import com.meeting.api.client.auth.RefreshResultDTO;
import com.meeting.api.domain.auth.RefreshToken;
import com.meeting.api.domain.auth.RefreshTokenRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuthApplicationServiceTest {

    @Test
    void refresh_issuesNewAccessToken() {
        InMemoryRefreshTokenRepository repo = new InMemoryRefreshTokenRepository();
        InMemoryAuthApplicationService service = new InMemoryAuthApplicationService(
            Clock.systemUTC(),
            AdminJwtCodec.defaults(),
            repo
        );

        RefreshToken validToken = new RefreshToken(
            "rt_123",
            "user_1",
            "tenant_1",
            OffsetDateTime.now().plusDays(10)
        );
        repo.save(validToken);

        RefreshResultDTO result = service.refresh("rt_123", "csrf_abc");

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.expiresAt()).isAfter(OffsetDateTime.now());
    }

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
}
