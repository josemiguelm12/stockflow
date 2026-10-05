package com.stockflow.identity.application;

import java.util.UUID;

/** Identidad consultada en el servidor en cada petición; el rol nunca se toma del JWT. */
public record AuthenticatedUser(UUID userId, String email, String role, UUID sessionId) {
}
