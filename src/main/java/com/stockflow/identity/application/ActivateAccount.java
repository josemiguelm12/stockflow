package com.stockflow.identity.application;

import com.stockflow.identity.domain.ActivationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class ActivateAccount {

    private final ActivationTokenRepository tokens;
    private final UserRepository users;
    private final Clock clock;

    ActivateAccount(ActivationTokenRepository tokens, UserRepository users, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Todo ocurre en una transacción: si cualquier paso falla, el token no queda consumido. Orden de bloqueo
     * fijo, igual que el reenvío: primero la fila del usuario y después sus tokens, para serializar el ciclo
     * de vida de activación por usuario sin posibilidad de deadlock.
     */
    @Transactional
    public void activate(String rawToken) {
        String hash = ActivationToken.hash(rawToken);
        UUID owner = tokens.findOwner(hash).orElseThrow(InvalidActivationTokenException::new);
        users.lockById(owner);

        Instant now = clock.instant();
        UUID userId = tokens.consume(hash, now).orElseThrow(InvalidActivationTokenException::new);
        if (!users.activate(userId, now)) {
            throw new InvalidActivationTokenException();
        }
        tokens.invalidatePending(userId, now);
    }
}
