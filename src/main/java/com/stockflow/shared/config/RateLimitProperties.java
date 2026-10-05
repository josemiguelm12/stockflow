package com.stockflow.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Límites por IP y minuto. Un valor ausente o vacío en el entorno usa el valor por defecto. */
@ConfigurationProperties("stockflow.rate-limit")
public record RateLimitProperties(Integer globalPerMinute, Integer loginPerMinute,
                                  Integer passwordForgotPerMinute, Integer passwordResetPerMinute,
                                  Integer passwordChangePerMinute) {

    public static final int DEFAULT_GLOBAL_PER_MINUTE = 120;
    public static final int DEFAULT_LOGIN_PER_MINUTE = 10;
    public static final int DEFAULT_PASSWORD_FORGOT_PER_MINUTE = 5;
    public static final int DEFAULT_PASSWORD_RESET_PER_MINUTE = 10;
    public static final int DEFAULT_PASSWORD_CHANGE_PER_MINUTE = 5;

    public RateLimitProperties {
        globalPerMinute = orDefault(globalPerMinute, DEFAULT_GLOBAL_PER_MINUTE);
        loginPerMinute = orDefault(loginPerMinute, DEFAULT_LOGIN_PER_MINUTE);
        passwordForgotPerMinute = orDefault(passwordForgotPerMinute, DEFAULT_PASSWORD_FORGOT_PER_MINUTE);
        passwordResetPerMinute = orDefault(passwordResetPerMinute, DEFAULT_PASSWORD_RESET_PER_MINUTE);
        passwordChangePerMinute = orDefault(passwordChangePerMinute, DEFAULT_PASSWORD_CHANGE_PER_MINUTE);
    }

    private static int orDefault(Integer value, int fallback) {
        int result = value == null ? fallback : value;
        if (result <= 0) {
            throw new IllegalArgumentException("stockflow.rate-limit values must be positive");
        }
        return result;
    }
}
