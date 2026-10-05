package com.stockflow.identity.application;

import java.util.Optional;
import java.util.UUID;

public interface AccessTokenVerifier {

    record VerifiedToken(UUID userId, UUID sessionId) {
    }

    /** Verifica firma, estructura y expiración. Vacío ante cualquier fallo, sin distinguir la causa. */
    Optional<VerifiedToken> verify(String token);
}
