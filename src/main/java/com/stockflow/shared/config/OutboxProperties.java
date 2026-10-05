package com.stockflow.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Clave y key id del cifrado del outbox; se validan al construir el cifrador para impedir el arranque si son inválidos. */
@ConfigurationProperties("stockflow.outbox")
public record OutboxProperties(String encryptionKey, String encryptionKeyId) {

    @Override
    public String toString() {
        return "OutboxProperties[encryptionKeyId=" + encryptionKeyId + "]";
    }
}
