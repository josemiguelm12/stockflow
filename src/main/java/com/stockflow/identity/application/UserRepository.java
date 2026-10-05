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

    /**
     * Bloquea (FOR UPDATE) la fila del usuario para evaluar un intento de login. Serializa los intentos de una
     * misma cuenta, así que el contador de fallos y el umbral de bloqueo no pierden incrementos concurrentes.
     */
    Optional<LoginAccount> lockForLogin(String emailNormalized);

    /** Guarda el contador de fallos consecutivos y el bloqueo vigente (puede ser null). */
    void recordFailedLogin(UUID userId, int failedAttempts, Instant lockedUntil, Instant now);

    /** Tras un login correcto: contador a 0 y sin bloqueo. */
    void clearLoginFailures(UUID userId, Instant now);

    /**
     * Bloquea (FOR UPDATE) la fila del usuario ACTIVE con ese email. Si otra transacción la modifica espera y
     * reevalúa el filtro: una cuenta que mientras tanto dejó de estar ACTIVE ya no coincide. Punto de
     * serialización de la recuperación de contraseña; requiere una transacción activa.
     */
    Optional<UUID> lockActiveIdByEmail(String emailNormalized);

    /**
     * Bloquea la fila del usuario ACTIVE y devuelve su hash de contraseña actual; vacío si no existe o no está
     * ACTIVE. Requiere una transacción activa.
     */
    Optional<String> lockActivePasswordHash(UUID userId);

    /** Guarda el nuevo hash, limpia password_reset_required y actualiza updated_at. No toca el lockout. */
    void updatePassword(UUID userId, String passwordHash, Instant now);

    /** Pasa PENDING_ACTIVATION a ACTIVE. Devuelve false si el usuario ya no estaba pendiente. */
    boolean activate(UUID userId, Instant now);
}
