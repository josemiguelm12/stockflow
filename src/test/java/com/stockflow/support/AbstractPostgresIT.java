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
        // Clave solo para pruebas (Base64 de 32 bytes), sin relación con ningún entorno real.
        "stockflow.outbox.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "stockflow.outbox.encryption-key-id=test-key-1"
})
public abstract class AbstractPostgresIT {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
