package com.stockflow.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Secreto HMAC en Base64 (32 bytes); se valida al construir el servicio JWT para impedir el arranque si es inválido. */
@ConfigurationProperties("stockflow.jwt")
public record JwtProperties(String secret) {

    @Override
    public String toString() {
        return "JwtProperties[redacted]";
    }
}
