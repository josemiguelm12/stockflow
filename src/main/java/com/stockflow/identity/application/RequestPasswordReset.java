package com.stockflow.identity.application;

import com.stockflow.identity.domain.ActivationToken;
import com.stockflow.identity.domain.EmailNormalizer;
import com.stockflow.shared.config.PasswordResetProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class RequestPasswordReset {

    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetEmailPort emails;
    private final PasswordResetProperties properties;
    private final Clock clock;

    RequestPasswordReset(UserRepository users, PasswordResetTokenRepository tokens, PasswordResetEmailPort emails,
                         PasswordResetProperties properties, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.emails = emails;
        this.properties = properties;
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
        users.lockActiveIdByEmail(normalized).ifPresent(userId -> {
            tokens.invalidatePending(userId, now);
            ActivationToken token = ActivationToken.generate(random);
            tokens.insert(UUID.randomUUID(), userId, token.hash(), now.plus(properties.tokenTtl()));
            emails.queue(normalized, token.raw());
        });
    }
}
