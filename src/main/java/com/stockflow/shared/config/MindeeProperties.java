package com.stockflow.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** Optional provider configuration: missing credentials disable only extraction. */
@ConfigurationProperties("stockflow.mindee")
public record MindeeProperties(String apiKey, String modelId, String baseUrl, Duration timeout) {
    public MindeeProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        modelId = modelId == null ? "" : modelId.trim();
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api-v2.mindee.net" : baseUrl.replaceAll("/+$", "");
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(20)) > 0) {
            throw new IllegalArgumentException("stockflow.mindee.timeout must be between 1ms and 20s");
        }
    }
    public boolean configured() { return !apiKey.isBlank() && !modelId.isBlank(); }
}
