package com.stockflow.identity.application;

import java.util.UUID;

/** Identidad consultada en el servidor en cada petición; el rol nunca se toma del JWT. */
public record AuthenticatedUser(UUID userId, String email, String role, UUID sessionId) {

    /** Es el principal de Spring Security, que puede registrarlo en DEBUG: sin email ni id de sesión. */
    @Override
    public String toString() {
        return "AuthenticatedUser[userId=" + userId + ", role=" + role + "]";
    }
}
