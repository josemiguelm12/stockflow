package com.stockflow.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Límites por IP y minuto. Un valor ausente o vacío en el entorno usa el valor por defecto. */
@ConfigurationProperties("stockflow.rate-limit")
public record RateLimitProperties(Integer globalPerMinute, Integer loginPerMinute) {

    public static final int DEFAULT_GLOBAL_PER_MINUTE = 120;
    public static final int DEFAULT_LOGIN_PER_MINUTE = 10;

    public RateLimitProperties {
        globalPerMinute = globalPerMinute == null ? DEFAULT_GLOBAL_PER_MINUTE : globalPerMinute;
        loginPerMinute = loginPerMinute == null ? DEFAULT_LOGIN_PER_MINUTE : loginPerMinute;
        if (globalPerMinute <= 0 || loginPerMinute <= 0) {
            throw new IllegalArgumentException("stockflow.rate-limit values must be positive");
        }
    }
}
