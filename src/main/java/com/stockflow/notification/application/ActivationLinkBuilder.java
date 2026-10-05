package com.stockflow.notification.application;

import com.stockflow.shared.config.ActivationProperties;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class ActivationLinkBuilder {

    private final String baseUrl;

    ActivationLinkBuilder(ActivationProperties properties) {
        this.baseUrl = properties.publicUrl();
    }

    /** El token va en el fragmento (#token=...), no en query string: no llega a logs de servidor. */
    public String build(String rawToken) {
        return baseUrl + "#token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }
}
