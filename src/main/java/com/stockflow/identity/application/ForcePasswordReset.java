package com.stockflow.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static com.stockflow.identity.application.AdminGuard.ACTIVE;
import static com.stockflow.identity.application.AdminGuard.conflict;

@Service
public class ForcePasswordReset {

    private static final String ACTION = "force_password_reset";

    private final UserAdministrationRepository admins;
    private final PasswordResetIssuer issuer;
    private final SessionRepository sessions;
    private final Clock clock;

    ForcePasswordReset(UserAdministrationRepository admins, PasswordResetIssuer issuer, SessionRepository sessions,
                       Clock clock) {
        this.admins = admins;
        this.issuer = issuer;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Bloquea primero la fila del objetivo y, en una sola transacción: marca password_reset_required, invalida los
     * tokens de recuperación anteriores, emite uno nuevo (mismas reglas que T03), encola el correo cifrado y revoca
     * todas las sesiones del objetivo (también la del ADMIN si se lo fuerza a sí mismo). No cambia el hash ni llama
     * a SMTP: la contraseña anterior deja de servir porque el login la rechaza mientras el flag esté activo.
     */
    @Transactional
    public void force(UUID actorId, UUID targetId) {
        var target = admins.lock(targetId).orElseThrow(UserNotFoundException::new);
        AdminGuard.requireActiveAdmin(admins, actorId);
        if (!ACTIVE.equals(target.accountStatus())) {
            throw conflict(ACTION, actorId, targetId, "target_not_active");
        }
        Instant now = clock.instant();
        admins.requirePasswordReset(targetId, now);
        issuer.issue(targetId, target.email(), now);
        sessions.revokeAllForUser(targetId, now);
        AdminAudit.record(ACTION, actorId, targetId, "queued");
    }
}
