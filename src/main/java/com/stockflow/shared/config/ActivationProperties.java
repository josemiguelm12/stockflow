package com.stockflow.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("stockflow.activation")
public record ActivationProperties(@NotBlank String publicUrl, @NotNull Duration tokenTtl) {

    public ActivationProperties {
        if (tokenTtl != null && (tokenTtl.isZero() || tokenTtl.isNegative())) {
            throw new IllegalArgumentException("stockflow.activation.token-ttl must be positive");
        }
    }
}
