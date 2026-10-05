package com.stockflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Instant;

/** Reloj controlable para las pruebas de capa web (@WebMvcTest no carga ApplicationConfig). */
@TestConfiguration
public class WebTestClock {

    @Bean
    MutableClock webTestClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
