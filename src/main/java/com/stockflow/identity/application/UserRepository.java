package com.stockflow.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    /** Inserta un usuario STANDARD en PENDING_ACTIVATION; lanza EmailAlreadyRegisteredException si el email ya existe. */
    void insertPending(UUID id, String emailNormalized, String passwordHash, Instant now);

    /**
     * Bloquea (FOR UPDATE) la fila del usuario pendiente con ese email. Si otra transacción la está modificando
     * espera a que termine y reevalúa: una cuenta que mientras tanto quedó ACTIVE ya no coincide. Es el punto de
     * serialización del ciclo de vida de activación; requiere una transacción activa.
     */
    Optional<UUID> lockPendingIdByEmail(String emailNormalized);

    /** Bloquea la fila del usuario (FOR UPDATE); requiere una transacción activa. */
    void lockById(UUID userId);

    /** Pasa PENDING_ACTIVATION a ACTIVE. Devuelve false si el usuario ya no estaba pendiente. */
    boolean activate(UUID userId, Instant now);
}
