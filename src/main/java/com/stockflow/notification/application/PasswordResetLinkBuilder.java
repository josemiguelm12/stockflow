package com.stockflow.notification.application;

import com.stockflow.shared.config.PasswordResetProperties;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class PasswordResetLinkBuilder {

    private final String baseUrl;

    PasswordResetLinkBuilder(PasswordResetProperties properties) {
        this.baseUrl = properties.publicUrl().trim();
    }

    /** El token va en el fragmento (#token=...), no en path ni query string: no llega a logs de servidor. */
    public String build(String rawToken) {
        return baseUrl + "#token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }
}
