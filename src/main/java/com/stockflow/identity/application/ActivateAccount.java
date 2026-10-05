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

    /** Todo ocurre en una transacción: si cualquier paso falla, el token no queda consumido. */
    @Transactional
    public void activate(String rawToken) {
        Instant now = clock.instant();
        UUID userId = tokens.consume(ActivationToken.hash(rawToken), now)
                .orElseThrow(InvalidActivationTokenException::new);
        if (!users.activate(userId, now)) {
            throw new InvalidActivationTokenException();
        }
        tokens.invalidatePending(userId, now);
    }
}
