package com.stockflow.identity.application;

import java.time.Instant;
import java.util.UUID;

public interface AccessTokenIssuer {

    /** JWT firmado con claims mínimos: sub (usuario), jti, iat y exp. */
    String issue(UUID userId, UUID sessionId, Instant issuedAt, Instant expiresAt);
}
