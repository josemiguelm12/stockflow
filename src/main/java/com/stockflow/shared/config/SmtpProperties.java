package com.stockflow.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opcional para la API web; el worker exige host, puerto y remitente al arrancar. */
@ConfigurationProperties("stockflow.smtp")
public record SmtpProperties(String host, Integer port, String username, String password, String from) {

    @Override
    public String toString() {
        return "SmtpProperties[host=" + host + ", port=" + port + "]";
    }
}
