package com.stockflow.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Recuperación de contraseña. La URL pública de la landing es obligatoria y debe ser absoluta (http/https): un
 * valor ausente o un marcador sin resolver impide el arranque en vez de generar enlaces rotos. La vigencia del
 * token es 30 minutos por defecto (también si la variable de entorno llega vacía).
 */
@ConfigurationProperties("stockflow.password-reset")
public record PasswordResetProperties(String publicUrl, Duration tokenTtl) {

    public static final Duration DEFAULT_TOKEN_TTL = Duration.ofMinutes(30);

    public PasswordResetProperties {
        tokenTtl = tokenTtl == null ? DEFAULT_TOKEN_TTL : tokenTtl;
        if (tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("stockflow.password-reset.token-ttl must be positive");
        }
        if (!isAbsoluteHttpUrl(publicUrl)) {
            throw new IllegalArgumentException(
                    "stockflow.password-reset.public-url must be an absolute http(s) URL (STOCKFLOW_PUBLIC_PASSWORD_RESET_URL)");
        }
    }

    private static boolean isAbsoluteHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
