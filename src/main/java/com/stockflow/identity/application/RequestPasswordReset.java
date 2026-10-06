package com.stockflow.identity.application;

import com.stockflow.identity.domain.EmailNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class RequestPasswordReset {

    private final UserRepository users;
    private final PasswordResetIssuer issuer;
    private final Clock clock;

    RequestPasswordReset(UserRepository users, PasswordResetIssuer issuer, Clock clock) {
        this.users = users;
        this.issuer = issuer;
        this.clock = clock;
    }

    /**
     * No revela si la cuenta existe ni su estado: solo una cuenta ACTIVE recibe token y correo; para el resto no
     * se muta nada. Orden de bloqueo fijo (usuario, luego tokens y outbox), igual que la activación: la fila del
     * usuario serializa solicitudes simultáneas, así que nunca queda más de un token vigente por usuario.
     */
    @Transactional
    public void request(String email) {
        String normalized = EmailNormalizer.normalize(email);
        if (!EmailNormalizer.isValid(normalized)) {
            throw new InvalidInputException("email", "must be a valid email address");
        }
        Instant now = clock.instant();
        users.lockActiveIdByEmail(normalized).ifPresent(userId -> issuer.issue(userId, normalized, now));
    }
}
