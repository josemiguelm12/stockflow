package com.stockflow.identity.application;

import java.util.UUID;

/** Comprobaciones comunes a los casos de uso administrativos. */
final class AdminGuard {

    static final String ADMIN = "ADMIN";
    static final String STANDARD = "STANDARD";
    static final String ACTIVE = "ACTIVE";
    static final String DISABLED = "DISABLED";

    private AdminGuard() {
    }

    /**
     * Revalida dentro de la transacción que quien actúa sigue siendo un ADMIN activo. Spring Security ya lo comprobó
     * al recibir la petición; esto cierra la ventana en la que otra operación concurrente lo degradó o desactivó.
     */
    static void requireActiveAdmin(UserAdministrationRepository admins, UUID actorId) {
        boolean ok = admins.find(actorId)
                .filter(actor -> ADMIN.equals(actor.role()) && ACTIVE.equals(actor.accountStatus()))
                .isPresent();
        if (!ok) {
            throw new AdminActorNotAuthorizedException();
        }
    }

    static AdminConflictException conflict(String action, UUID actorId, UUID targetId, String reason) {
        AdminAudit.record(action, actorId, targetId, "rejected:" + reason);
        return new AdminConflictException(reason);
    }
}
