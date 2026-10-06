package com.stockflow.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.identity.application.AdminGuard.ACTIVE;
import static com.stockflow.identity.application.AdminGuard.ADMIN;
import static com.stockflow.identity.application.AdminGuard.STANDARD;
import static com.stockflow.identity.application.AdminGuard.conflict;

@Service
public class ChangeUserRole {

    private static final String ACTION = "change_role";
    private static final Set<String> ROLES = Set.of(ADMIN, STANDARD);

    private final UserAdministrationRepository admins;
    private final Clock clock;

    ChangeUserRole(UserAdministrationRepository admins, Clock clock) {
        this.admins = admins;
        this.clock = clock;
    }

    /**
     * Serializado con el advisory lock de pertenencia a ADMIN: comprobar "último ADMIN activo" y mutar ocurre sin que
     * otro cambio de rol/estado pueda intercalarse, así que dos degradaciones simultáneas no dejan cero ADMIN.
     * El nuevo rol se aplica en la siguiente petición del afectado (el rol se consulta en cada request).
     */
    @Transactional
    public void change(UUID actorId, UUID targetId, String role) {
        if (role == null || !ROLES.contains(role)) {
            throw new InvalidInputException("role", "must be ADMIN or STANDARD");
        }
        admins.acquireAdminMembershipLock();
        AdminGuard.requireActiveAdmin(admins, actorId);
        var target = admins.lock(targetId).orElseThrow(UserNotFoundException::new);

        if (target.id().equals(actorId)) {
            throw conflict(ACTION, actorId, targetId, "self_role_change");
        }
        if (target.role().equals(role)) {
            AdminAudit.record(ACTION, actorId, targetId, "unchanged");
            return;
        }
        if (ADMIN.equals(target.role()) && ACTIVE.equals(target.accountStatus()) && admins.countActiveAdmins() <= 1) {
            throw conflict(ACTION, actorId, targetId, "last_active_admin");
        }
        admins.updateRole(targetId, role, clock.instant());
        AdminAudit.record(ACTION, actorId, targetId, "changed_to_" + role);
    }
}
