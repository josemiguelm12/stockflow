package com.stockflow.identity.application;

import com.stockflow.identity.domain.ActivationToken;
import com.stockflow.shared.config.ActivationProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/** Genera un token de activación, persiste su hash y encola el correo. Debe llamarse dentro de una transacción. */
@Component
class ActivationIssuer {

    private final SecureRandom random = new SecureRandom();
    private final ActivationTokenRepository tokens;
    private final ActivationEmailPort emails;
    private final ActivationProperties properties;

    ActivationIssuer(ActivationTokenRepository tokens, ActivationEmailPort emails, ActivationProperties properties) {
        this.tokens = tokens;
        this.emails = emails;
        this.properties = properties;
    }

    void issue(UUID userId, String emailNormalized, Instant now) {
        ActivationToken token = ActivationToken.generate(random);
        tokens.insert(UUID.randomUUID(), userId, token.hash(), now.plus(properties.tokenTtl()));
        emails.queue(emailNormalized, token.raw());
    }
}
