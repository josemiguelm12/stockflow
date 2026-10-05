package com.stockflow.shared.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordRecoveryPropertiesTest {

    @Test
    void passwordResetTokenTtlDefaultsToThirtyMinutes() {
        assertThat(new PasswordResetProperties("http://localhost:8080/reset-password", null).tokenTtl())
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(new PasswordResetProperties("http://localhost:8080/reset-password", Duration.ofMinutes(5)).tokenTtl())
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void passwordResetTtlMustBePositive() {
        assertThatThrownBy(() -> new PasswordResetProperties("http://localhost:8080/reset-password", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordResetProperties("http://localhost:8080/reset-password", Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePublicUrlIsRequiredAndMustBeAbsoluteHttp() {
        for (String bad : new String[]{null, "", "  ", "${STOCKFLOW_PUBLIC_PASSWORD_RESET_URL}", "/reset-password",
                "ftp://example.test/reset", "http://", "not a url"}) {
            assertThatThrownBy(() -> new PasswordResetProperties(bad, null)).as(String.valueOf(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new PasswordResetProperties("https://app.example.test/reset-password", null).publicUrl())
                .isEqualTo("https://app.example.test/reset-password");
    }

    @Test
    void passwordRateLimitsDefaultToFiveTenAndFive() {
        var defaults = new RateLimitProperties(null, null, null, null, null);

        assertThat(defaults.globalPerMinute()).isEqualTo(120);
        assertThat(defaults.loginPerMinute()).isEqualTo(10);
        assertThat(defaults.passwordForgotPerMinute()).isEqualTo(5);
        assertThat(defaults.passwordResetPerMinute()).isEqualTo(10);
        assertThat(defaults.passwordChangePerMinute()).isEqualTo(5);
    }

    @Test
    void passwordRateLimitsAreConfigurableAndMustBePositive() {
        var custom = new RateLimitProperties(7, 8, 1, 2, 3);
        assertThat(custom.passwordForgotPerMinute()).isEqualTo(1);
        assertThat(custom.passwordResetPerMinute()).isEqualTo(2);
        assertThat(custom.passwordChangePerMinute()).isEqualTo(3);

        assertThatThrownBy(() -> new RateLimitProperties(null, null, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(null, null, null, -1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitProperties(null, null, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
