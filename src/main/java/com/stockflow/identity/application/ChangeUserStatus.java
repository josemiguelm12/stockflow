package com.stockflow.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.identity.application.AdminGuard.ACTIVE;
import static com.stockflow.identity.application.AdminGuard.ADMIN;
import static com.stockflow.identity.application.AdminGuard.DISABLED;
import static com.stockflow.identity.application.AdminGuard.conflict;

@Service
public class ChangeUserStatus {

    private static final String ACTION = "change_status";
    /** PENDING_ACTIVATION no se puede asignar ni consultar por esta operación. */
    private static final Set<String> STATUSES = Set.of(ACTIVE, DISABLED);

    private final UserAdministrationRepository admins;
    private final SessionRepository sessions;
    private final Clock clock;

    ChangeUserStatus(UserAdministrationRepository admins, SessionRepository sessions, Clock clock) {
        this.admins = admins;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Desactivar: exige ACTIVE, no permite auto-desactivarse ni dejar cero ADMIN activos, y revoca todas las sesiones
     * del objetivo en la misma transacción. Reactivar: exige DISABLED y haber sido activado alguna vez; no revive
     * sesiones ni toca el bloqueo de login. Pedir el estado actual es idempotente (sin efectos).
     */
    @Transactional
    public void change(UUID actorId, UUID targetId, String accountStatus) {
        if (accountStatus == null || !STATUSES.contains(accountStatus)) {
            throw new InvalidInputException("accountStatus", "must be ACTIVE or DISABLED");
        }
        admins.acquireAdminMembershipLock();
        AdminGuard.requireActiveAdmin(admins, actorId);
        var target = admins.lock(targetId).orElseThrow(UserNotFoundException::new);
        Instant now = clock.instant();

        if (DISABLED.equals(accountStatus)) {
            if (target.id().equals(actorId)) {
                throw conflict(ACTION, actorId, targetId, "self_disable");
            }
            if (DISABLED.equals(target.accountStatus())) {
                AdminAudit.record(ACTION, actorId, targetId, "unchanged");
                return;
            }
            if (!ACTIVE.equals(target.accountStatus())) {
                throw conflict(ACTION, actorId, targetId, "invalid_transition");
            }
            if (ADMIN.equals(target.role()) && admins.countActiveAdmins() <= 1) {
                throw conflict(ACTION, actorId, targetId, "last_active_admin");
            }
            admins.updateStatus(targetId, DISABLED, now);
            sessions.revokeAllForUser(targetId, now);
            AdminAudit.record(ACTION, actorId, targetId, "disabled");
            return;
        }

        if (ACTIVE.equals(target.accountStatus())) {
            AdminAudit.record(ACTION, actorId, targetId, "unchanged");
            return;
        }
        if (!DISABLED.equals(target.accountStatus()) || !target.everActivated()) {
            throw conflict(ACTION, actorId, targetId, "invalid_transition");
        }
        admins.updateStatus(targetId, ACTIVE, now);
        AdminAudit.record(ACTION, actorId, targetId, "reactivated");
    }
}
