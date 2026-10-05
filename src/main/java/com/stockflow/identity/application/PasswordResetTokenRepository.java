package com.stockflow.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Tokens de propósito PASSWORD_RESET; cualquier token de otro propósito es invisible para este puerto. */
public interface PasswordResetTokenRepository {

    void insert(UUID id, UUID userId, String tokenHash, Instant expiresAt);

    /** Dueño del token de recuperación (sin importar su estado), para bloquear al usuario antes de consumirlo. */
    Optional<UUID> findOwner(String tokenHash);

    /** Consume de forma atómica un token vigente (expires_at > now, no consumido, no invalidado). */
    Optional<UUID> consume(String tokenHash, Instant now);

    /** Invalida los tokens de recuperación aún pendientes del usuario. */
    void invalidatePending(UUID userId, Instant now);
}
