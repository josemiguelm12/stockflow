package com.stockflow.shared.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({ActivationProperties.class, PasswordResetProperties.class, OutboxProperties.class,
        SmtpProperties.class, DocumentProperties.class, MindeeProperties.class})
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
