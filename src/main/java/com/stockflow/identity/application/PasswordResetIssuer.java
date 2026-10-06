package com.stockflow.identity.application;

import com.stockflow.identity.domain.ActivationToken;
import com.stockflow.shared.config.PasswordResetProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Emite un token de recuperación: invalida los anteriores no consumidos, genera 32 bytes CSPRNG, persiste solo su
 * SHA-256 con la vigencia configurada y encola el correo PASSWORD_RESET cifrado. Debe llamarse dentro de una
 * transacción y con la fila del usuario ya bloqueada (lo usan la solicitud pública y el reset forzado por ADMIN).
 */
@Component
class PasswordResetIssuer {

    private final SecureRandom random = new SecureRandom();
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetEmailPort emails;
    private final PasswordResetProperties properties;

    PasswordResetIssuer(PasswordResetTokenRepository tokens, PasswordResetEmailPort emails,
                        PasswordResetProperties properties) {
        this.tokens = tokens;
        this.emails = emails;
        this.properties = properties;
    }

    void issue(UUID userId, String emailNormalized, Instant now) {
        tokens.invalidatePending(userId, now);
        ActivationToken token = ActivationToken.generate(random);
        tokens.insert(UUID.randomUUID(), userId, token.hash(), now.plus(properties.tokenTtl()));
        emails.queue(emailNormalized, token.raw());
    }
}
