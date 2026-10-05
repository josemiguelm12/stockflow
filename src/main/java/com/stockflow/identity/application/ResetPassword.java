package com.stockflow.identity.application;

import com.stockflow.identity.domain.ActivationToken;
import com.stockflow.identity.domain.PasswordPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ResetPassword {

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordHasher hasher;
    private final Clock clock;

    ResetPassword(PasswordResetTokenRepository tokens, UserRepository users, SessionRepository sessions,
                  PasswordHasher hasher, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.sessions = sessions;
        this.hasher = hasher;
        this.clock = clock;
    }

    /**
     * Todo en una transacción; si cualquier paso falla no queda nada a medias. La política de contraseña se
     * valida primero, antes de tocar el token, así que una contraseña inválida no lo consume. Orden de bloqueo
     * fijo: fila del usuario y después tokens y sesiones. No crea sesión ni devuelve JWT.
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        PasswordPolicy.violation(newPassword).ifPresent(reason -> {
            throw new InvalidInputException("newPassword", reason);
        });

        String tokenHash = ActivationToken.hash(rawToken);
        UUID owner = tokens.findOwner(tokenHash).orElseThrow(InvalidPasswordResetTokenException::new);
        // El usuario debe seguir ACTIVE; si no, el token es inservible (misma respuesta que cualquier otro fallo).
        users.lockActivePasswordHash(owner).orElseThrow(InvalidPasswordResetTokenException::new);

        Instant now = clock.instant();
        UUID userId = tokens.consume(tokenHash, now).orElseThrow(InvalidPasswordResetTokenException::new);
        users.updatePassword(userId, hasher.hash(newPassword), now);
        tokens.invalidatePending(userId, now);
        sessions.revokeAllForUser(userId, now);
    }
}
