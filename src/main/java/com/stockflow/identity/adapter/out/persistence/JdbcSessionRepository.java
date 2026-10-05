package com.stockflow.identity.adapter.out.persistence;

import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.SessionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcSessionRepository implements SessionRepository {

    private final JdbcClient jdbc;

    JdbcSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID sessionId, UUID userId, Instant issuedAt, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO auth_sessions (jti, user_id, issued_at, expires_at)
                VALUES (:jti, :userId, :issuedAt, :expiresAt)
                """)
                .param("jti", sessionId)
                .param("userId", userId)
                .param("issuedAt", utc(issuedAt))
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    @Override
    public void revoke(UUID sessionId, Instant now) {
        jdbc.sql("UPDATE auth_sessions SET revoked_at = :now WHERE jti = :jti AND revoked_at IS NULL")
                .param("jti", sessionId)
                .param("now", utc(now))
                .update();
    }

    @Override
    public void revokeAllForUser(UUID userId, Instant now) {
        jdbc.sql("UPDATE auth_sessions SET revoked_at = :now WHERE user_id = :userId AND revoked_at IS NULL")
                .param("userId", userId)
                .param("now", utc(now))
                .update();
    }

    @Override
    public Optional<AuthenticatedUser> findActive(UUID sessionId, UUID userId, Instant now) {
        return jdbc.sql("""
                SELECT u.id, u.email_normalized, u.role
                FROM auth_sessions s JOIN users u ON u.id = s.user_id
                WHERE s.jti = :jti AND s.user_id = :userId
                  AND s.revoked_at IS NULL AND s.expires_at > :now
                  AND u.account_status = 'ACTIVE'
                """)
                .param("jti", sessionId)
                .param("userId", userId)
                .param("now", utc(now))
                .query((rs, row) -> new AuthenticatedUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("email_normalized"),
                        rs.getString("role"),
                        sessionId))
                .optional();
    }
}
