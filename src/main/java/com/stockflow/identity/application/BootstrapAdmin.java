package com.stockflow.identity.application;

import com.stockflow.identity.domain.EmailNormalizer;
import com.stockflow.identity.domain.PasswordPolicy;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static com.stockflow.identity.application.AdminGuard.ACTIVE;
import static com.stockflow.identity.application.AdminGuard.ADMIN;

/**
 * Bootstrap del primer ADMIN. Solo se construye en el proceso de bootstrap (no es un @Service de la aplicación web).
 * El advisory lock de pertenencia a ADMIN serializa ejecuciones simultáneas (y cualquier cambio de rol/estado por la
 * API), así que dos procesos nunca crean o promueven dos "primeros" ADMIN.
 */
public class BootstrapAdmin {

    public enum Outcome {
        CREATED("created"), PROMOTED("promoted"), ALREADY_PRESENT("already-present");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final UserAdministrationRepository admins;
    private final PasswordResetTokenRepository resetTokens;
    private final SessionRepository sessions;
    private final PasswordHasher hasher;
    private final Clock clock;

    public BootstrapAdmin(UserAdministrationRepository admins, PasswordResetTokenRepository resetTokens,
                          SessionRepository sessions, PasswordHasher hasher, Clock clock) {
        this.admins = admins;
        this.resetTokens = resetTokens;
        this.sessions = sessions;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Transactional
    public Outcome bootstrap(String email, String password) {
        String normalized = EmailNormalizer.normalize(email);
        if (!EmailNormalizer.isValid(normalized)) {
            throw new AdminBootstrapException("STOCKFLOW_ADMIN_BOOTSTRAP_EMAIL must be a valid email address");
        }
        PasswordPolicy.violation(password).ifPresent(reason -> {
            throw new AdminBootstrapException("STOCKFLOW_ADMIN_BOOTSTRAP_PASSWORD " + reason);
        });

        admins.acquireAdminMembershipLock();
        var existing = admins.lockByEmail(normalized);
        if (existing.isPresent() && ADMIN.equals(existing.get().role())) {
            return Outcome.ALREADY_PRESENT;
        }
        if (admins.countAdmins() > 0) {
            throw new AdminBootstrapException(
                    "an ADMIN already exists with a different email; promote further ADMIN users through the API");
        }

        Instant now = clock.instant();
        if (existing.isEmpty()) {
            admins.insertActiveAdmin(UUID.randomUUID(), normalized, hasher.hash(password), now);
            return Outcome.CREATED;
        }
        var user = existing.get();
        if (!ACTIVE.equals(user.accountStatus())) {
            throw new AdminBootstrapException("the configured account exists but is not ACTIVE");
        }
        admins.promoteToAdmin(user.id(), hasher.hash(password), now);
        resetTokens.invalidatePending(user.id(), now);
        sessions.revokeAllForUser(user.id(), now);
        return Outcome.PROMOTED;
    }
}
