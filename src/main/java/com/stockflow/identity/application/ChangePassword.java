package com.stockflow.identity.application;

import com.stockflow.identity.domain.PasswordPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ChangePassword {

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final SessionRepository sessions;
    private final PasswordHasher hasher;
    private final Clock clock;

    ChangePassword(UserRepository users, PasswordResetTokenRepository tokens, SessionRepository sessions,
                   PasswordHasher hasher, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.sessions = sessions;
        this.hasher = hasher;
        this.clock = clock;
    }

    /**
     * Cambia la contraseña del usuario autenticado (el id viene del Bearer, nunca del cliente). La contraseña
     * actual se verifica antes de cualquier mutación; si es incorrecta no cambia nada. No toca el contador de
     * fallos ni el bloqueo de login. Revoca TODAS las sesiones del usuario, incluida la que autorizó la petición.
     */
    @Transactional
    public void change(UUID userId, String currentPassword, String newPassword) {
        PasswordPolicy.violation(newPassword).ifPresent(reason -> {
            throw new InvalidInputException("newPassword", reason);
        });

        users.lockActivePasswordHash(userId)
                .filter(currentHash -> hasher.matches(currentPassword, currentHash))
                .orElseThrow(InvalidCurrentPasswordException::new);

        Instant now = clock.instant();
        users.updatePassword(userId, hasher.hash(newPassword), now);
        tokens.invalidatePending(userId, now);
        sessions.revokeAllForUser(userId, now);
    }
}
