package com.stockflow.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** Un único PostgreSQL real por ejecución de pruebas, compartido por todas las pruebas de integración. */
public final class SharedPostgres {

    public static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:16-alpine");

    static {
        CONTAINER.start();
    }

    private SharedPostgres() {
    }
}
