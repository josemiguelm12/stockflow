package com.stockflow.identity.application;

import com.stockflow.identity.domain.EmailNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class ResendActivation {

    private final UserRepository users;
    private final ActivationTokenRepository tokens;
    private final ActivationIssuer issuer;
    private final Clock clock;

    ResendActivation(UserRepository users, ActivationTokenRepository tokens, ActivationIssuer issuer, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.issuer = issuer;
        this.clock = clock;
    }

    /**
     * No revela si la cuenta existe: para inexistentes o ya activas no hace nada. Bloquea la fila del usuario
     * pendiente antes de invalidar/emitir, de modo que dos reenvíos, o un reenvío y una activación, se
     * ejecutan uno tras otro y nunca quedan dos tokens utilizables ni un token nuevo para una cuenta ACTIVE.
     */
    @Transactional
    public void resend(String email) {
        String normalized = EmailNormalizer.normalize(email);
        if (!EmailNormalizer.isValid(normalized)) {
            throw new InvalidInputException("email", "must be a valid email address");
        }
        Instant now = clock.instant();
        users.lockPendingIdByEmail(normalized).ifPresent(userId -> {
            tokens.invalidatePending(userId, now);
            issuer.issue(userId, normalized, now);
        });
    }
}
