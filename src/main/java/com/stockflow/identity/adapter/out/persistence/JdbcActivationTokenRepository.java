package com.stockflow.identity.adapter.out.persistence;

import com.stockflow.identity.application.ActivationTokenRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcActivationTokenRepository implements ActivationTokenRepository {

    private final JdbcClient jdbc;

    JdbcActivationTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO one_time_tokens (id, user_id, purpose, token_hash, expires_at)
                VALUES (:id, :userId, 'ACTIVATION', :hash, :expiresAt)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    @Override
    public Optional<UUID> findOwner(String tokenHash) {
        return jdbc.sql("SELECT user_id FROM one_time_tokens WHERE purpose = 'ACTIVATION' AND token_hash = :hash")
                .param("hash", tokenHash)
                .query(UUID.class)
                .optional();
    }

    /** Un único UPDATE condicionado: dos peticiones concurrentes con el mismo token no pueden consumirlo ambas. */
    @Override
    public Optional<UUID> consume(String tokenHash, Instant now) {
        return jdbc.sql("""
                UPDATE one_time_tokens
                SET consumed_at = :now
                WHERE purpose = 'ACTIVATION' AND token_hash = :hash
                  AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > :now
                RETURNING user_id
                """)
                .param("hash", tokenHash)
                .param("now", utc(now))
                .query(UUID.class)
                .optional();
    }

    @Override
    public void invalidatePending(UUID userId, Instant now) {
        jdbc.sql("""
                UPDATE one_time_tokens
                SET invalidated_at = :now
                WHERE user_id = :userId AND purpose = 'ACTIVATION'
                  AND consumed_at IS NULL AND invalidated_at IS NULL
                """)
                .param("userId", userId)
                .param("now", utc(now))
                .update();
    }
}
