package com.stockflow.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** Base de las pruebas de integración: un único PostgreSQL real (Testcontainers) compartido por todas. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestBeans.class)
@TestPropertySource(properties = {
        "stockflow.cors.allowed-origins=http://localhost:4200",
        "stockflow.activation.public-url=http://localhost:8080/activate",
        "stockflow.activation.token-ttl=PT24H",
        "stockflow.password-reset.public-url=http://localhost:8080/reset-password",
        // Clave solo para pruebas (Base64 de 32 bytes), sin relación con ningún entorno real.
        "stockflow.outbox.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "stockflow.outbox.encryption-key-id=test-key-1",
        // Secreto JWT solo para pruebas (Base64 de 32 bytes distintos de la clave del outbox).
        "stockflow.jwt.secret=" + AbstractPostgresIT.JWT_SECRET,
        // Límites altos: las ITs hacen muchas peticiones desde 127.0.0.1; RateLimitIT los baja a propósito.
        "stockflow.rate-limit.global-per-minute=100000",
        "stockflow.rate-limit.login-per-minute=100000",
        "stockflow.rate-limit.password-forgot-per-minute=100000",
        "stockflow.rate-limit.password-reset-per-minute=100000",
        "stockflow.rate-limit.password-change-per-minute=100000"
})
public abstract class AbstractPostgresIT {

    public static final String JWT_SECRET = "dGVzdC1qd3Qtc2VjcmV0LWZvci1zdG9ja2Zsb3ctMDE=";

    protected static final PostgreSQLContainer POSTGRES = SharedPostgres.CONTAINER;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
