package com.stockflow.identity.application;

import java.time.Clock;
import java.util.Optional;

/**
 * Autentica un Bearer en cada petición: firma y expiración del JWT, sesión persistida vigente y
 * estado actual ACTIVE del usuario. Rol y email vienen de la base de datos, no del token.
 */
public class AuthenticateSession {

    private final AccessTokenVerifier verifier;
    private final SessionRepository sessions;
    private final Clock clock;

    public AuthenticateSession(AccessTokenVerifier verifier, SessionRepository sessions, Clock clock) {
        this.verifier = verifier;
        this.sessions = sessions;
        this.clock = clock;
    }

    public Optional<AuthenticatedUser> authenticate(String bearerToken) {
        return verifier.verify(bearerToken)
                .flatMap(token -> sessions.findActive(token.sessionId(), token.userId(), clock.instant()));
    }
}
