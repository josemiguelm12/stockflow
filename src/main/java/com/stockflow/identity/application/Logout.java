package com.stockflow.identity.application;

import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

public class Logout {

    private final SessionRepository sessions;
    private final Clock clock;

    public Logout(SessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Revoca la sesión identificada por el jti; el JWT deja de servir en la siguiente petición. */
    @Transactional
    public void logout(UUID sessionId) {
        sessions.revoke(sessionId, clock.instant());
    }
}
