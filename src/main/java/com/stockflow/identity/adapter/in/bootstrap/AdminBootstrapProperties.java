package com.stockflow.identity.adapter.in.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Sin valores por defecto ni credenciales de demostración; con el bootstrap deshabilitado pueden faltar. */
@ConfigurationProperties("stockflow.admin-bootstrap")
public record AdminBootstrapProperties(boolean enabled, String email, String password) {

    @Override
    public String toString() {
        return "AdminBootstrapProperties[enabled=" + enabled + ", credentials=redacted]";
    }
}
