package com.stockflow.shared.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties("stockflow.cors")
public record CorsProperties(@NotEmpty List<String> allowedOrigins) {

    public CorsProperties {
        if (allowedOrigins != null && allowedOrigins.stream().anyMatch(o -> o.isBlank() || o.contains("*"))) {
            throw new IllegalArgumentException("stockflow.cors.allowed-origins must list explicit origins, no wildcards");
        }
    }
}
