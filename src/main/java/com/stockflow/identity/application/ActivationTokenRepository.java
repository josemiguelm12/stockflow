package com.stockflow.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ActivationTokenRepository {

    void insert(UUID id, UUID userId, String tokenHash, Instant expiresAt);

    /** Consume de forma atómica un token vigente; devuelve el usuario dueño o vacío si no es utilizable. */
    Optional<UUID> consume(String tokenHash, Instant now);

    /** Invalida los tokens de activación aún pendientes del usuario. */
    void invalidatePending(UUID userId, Instant now);
}
