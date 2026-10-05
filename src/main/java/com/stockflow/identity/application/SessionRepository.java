package com.stockflow.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {

    /** Persiste solo el jti y las fechas; el JWT completo nunca se guarda. */
    void insert(UUID sessionId, UUID userId, Instant issuedAt, Instant expiresAt);

    void revoke(UUID sessionId, Instant now);

    /**
     * Usuario dueño de una sesión vigente: la sesión existe, pertenece a ese usuario, no está revocada ni
     * expirada, y el usuario está hoy ACTIVE. Rol y email se leen en esta consulta.
     */
    Optional<AuthenticatedUser> findActive(UUID sessionId, UUID userId, Instant now);
}
