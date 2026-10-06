package com.stockflow.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistencia de las operaciones administrativas y del bootstrap del primer ADMIN. */
public interface UserAdministrationRepository {

    /** Usuario tal como lo ve la administración. {@code everActivated} = activation_completed_at no nulo. */
    record ManagedUser(UUID id, String email, String role, String accountStatus, boolean everActivated) {

        @Override
        public String toString() {
            return "ManagedUser[id=" + id + ", role=" + role + ", accountStatus=" + accountStatus + "]";
        }
    }

    /** Fila del listado: solo campos seguros (sin hash, fallos, bloqueo, tokens ni sesiones). */
    record UserSummary(UUID id, String email, String role, String accountStatus, boolean passwordResetRequired,
                       Instant createdAt, Instant updatedAt) {

        @Override
        public String toString() {
            return "UserSummary[id=" + id + ", role=" + role + ", accountStatus=" + accountStatus + "]";
        }
    }

    /**
     * Bloqueo transaccional (advisory lock de PostgreSQL, se libera al terminar la transacción) que serializa
     * cualquier cambio que pueda alterar el conjunto de ADMIN activos: cambio de rol, cambio de estado y bootstrap.
     * Se toma siempre antes que cualquier bloqueo de fila, así que no puede formar ciclos con ellos.
     */
    void acquireAdminMembershipLock();

    List<UserSummary> page(int size, long offset);

    long countUsers();

    /** Lectura sin bloqueo (p. ej. para revalidar al ADMIN que actúa). */
    Optional<ManagedUser> find(UUID userId);

    /** Bloquea (FOR UPDATE) la fila del usuario objetivo. */
    Optional<ManagedUser> lock(UUID userId);

    /** Bloquea (FOR UPDATE) la fila del usuario con ese email normalizado. */
    Optional<ManagedUser> lockByEmail(String emailNormalized);

    long countActiveAdmins();

    long countAdmins();

    void updateRole(UUID userId, String role, Instant now);

    void updateStatus(UUID userId, String accountStatus, Instant now);

    void requirePasswordReset(UUID userId, Instant now);

    /** Crea un ADMIN ACTIVE ya activado, sin reset pendiente y con contadores de login limpios. */
    void insertActiveAdmin(UUID id, String emailNormalized, String passwordHash, Instant now);

    /** Promueve a ADMIN y reemplaza la contraseña; limpia password_reset_required. */
    void promoteToAdmin(UUID userId, String passwordHash, Instant now);
}
