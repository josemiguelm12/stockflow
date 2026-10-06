package com.stockflow.identity.adapter.in.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** El modo bootstrap solo se activa de forma explícita; por defecto arranca la aplicación normal. */
class AdminBootstrapModeTest {

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty("stockflow.admin-bootstrap.enabled");
    }

    @Test
    void isOffByDefault() {
        assertThat(AdminBootstrapApplication.isRequested(new String[0])).isFalse();
        assertThat(AdminBootstrapApplication.isRequested(new String[]{"--server.port=8081"})).isFalse();
    }

    @Test
    void isOnlyOnWithAnExplicitTrue() {
        assertThat(AdminBootstrapApplication.isRequested(new String[]{"--stockflow.admin-bootstrap.enabled=true"})).isTrue();
        assertThat(AdminBootstrapApplication.isRequested(new String[]{"--stockflow.admin-bootstrap.enabled=false"})).isFalse();
        assertThat(AdminBootstrapApplication.isRequested(new String[]{"--stockflow.admin-bootstrap.enabled=yes"})).isFalse();
    }

    @Test
    void readsTheSystemProperty() {
        System.setProperty("stockflow.admin-bootstrap.enabled", "true");

        assertThat(AdminBootstrapApplication.isRequested(new String[0])).isTrue();
    }

    @Test
    void thePropertiesNeverPrintTheCredentials() {
        var properties = new AdminBootstrapProperties(true, "admin@example.test", "S3cret-bootstrap");

        assertThat(properties.toString()).doesNotContain("admin@example.test").doesNotContain("S3cret-bootstrap");
    }
}
