package com.stockflow.identity.adapter.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

/**
 * Acceso a one_time_tokens para UN propósito (ACTIVATION o PASSWORD_RESET). Toda consulta filtra por propósito:
 * un token de un propósito nunca es visible, consumible ni invalidable desde el otro.
 */
final class OneTimeTokenTable {

    private final JdbcClient jdbc;
    private final String purpose;

    OneTimeTokenTable(JdbcClient jdbc, String purpose) {
        this.jdbc = jdbc;
        this.purpose = purpose;
    }

    void insert(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO one_time_tokens (id, user_id, purpose, token_hash, expires_at)
                VALUES (:id, :userId, :purpose, :hash, :expiresAt)
                """)
                .param("id", id)
                .param("userId", userId)
                .param("purpose", purpose)
                .param("hash", tokenHash)
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    Optional<UUID> findOwner(String tokenHash) {
        return jdbc.sql("SELECT user_id FROM one_time_tokens WHERE purpose = :purpose AND token_hash = :hash")
                .param("purpose", purpose)
                .param("hash", tokenHash)
                .query(UUID.class)
                .optional();
    }

    /** Un único UPDATE condicionado: dos peticiones concurrentes con el mismo token no pueden consumirlo ambas. */
    Optional<UUID> consume(String tokenHash, Instant now) {
        return jdbc.sql("""
                UPDATE one_time_tokens
                SET consumed_at = :now
                WHERE purpose = :purpose AND token_hash = :hash
                  AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > :now
                RETURNING user_id
                """)
                .param("purpose", purpose)
                .param("hash", tokenHash)
                .param("now", utc(now))
                .query(UUID.class)
                .optional();
    }

    void invalidatePending(UUID userId, Instant now) {
        jdbc.sql("""
                UPDATE one_time_tokens
                SET invalidated_at = :now
                WHERE user_id = :userId AND purpose = :purpose
                  AND consumed_at IS NULL AND invalidated_at IS NULL
                """)
                .param("userId", userId)
                .param("purpose", purpose)
                .param("now", utc(now))
                .update();
    }
}
