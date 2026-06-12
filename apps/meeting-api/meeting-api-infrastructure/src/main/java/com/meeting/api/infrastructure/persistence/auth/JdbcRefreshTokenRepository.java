package com.meeting.api.infrastructure.persistence.auth;

import com.meeting.api.domain.auth.RefreshToken;
import com.meeting.api.domain.auth.RefreshTokenRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {
    private final JdbcTemplate jdbc;

    public JdbcRefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(RefreshToken token) {
        jdbc.update(
            "INSERT INTO refresh_tokens (token_id, user_id, tenant_id, expires_at) VALUES (?, ?, ?, ?)",
            token.tokenId(), token.userId(), token.tenantId(), token.expiresAt()
        );
    }

    @Override
    public Optional<RefreshToken> findByTokenId(String tokenId) {
        var rows = jdbc.query(
            "SELECT token_id, user_id, tenant_id, expires_at FROM refresh_tokens WHERE token_id = ?",
            this::mapRow, tokenId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void revokeByTokenId(String tokenId) {
        jdbc.update("DELETE FROM refresh_tokens WHERE token_id = ?", tokenId);
    }

    @Override
    public void revokeAllByUserId(String userId) {
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
    }

    private RefreshToken mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RefreshToken(
            rs.getString("token_id"),
            rs.getString("user_id"),
            rs.getString("tenant_id"),
            rs.getObject("expires_at", OffsetDateTime.class)
        );
    }
}
