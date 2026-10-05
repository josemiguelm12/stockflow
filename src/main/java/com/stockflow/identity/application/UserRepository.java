package com.stockflow.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    /** Inserta un usuario STANDARD en PENDING_ACTIVATION; lanza EmailAlreadyRegisteredException si el email ya existe. */
    void insertPending(UUID id, String emailNormalized, String passwordHash, Instant now);

    Optional<UUID> findPendingIdByEmail(String emailNormalized);

    /** Pasa PENDING_ACTIVATION a ACTIVE. Devuelve false si el usuario ya no estaba pendiente. */
    boolean activate(UUID userId, Instant now);
}
