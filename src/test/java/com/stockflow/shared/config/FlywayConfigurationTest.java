package com.stockflow.shared.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FlywayConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(FlywayAutoConfiguration.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(FlywayMigrationStrategy.class, () -> flyway -> { })
            .withPropertyValues("stockflow.cors.allowed-origins=http://localhost:4200");

    @Test
    void flywayIsEnabledWithApplicationProperties() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Flyway.class);
            assertThat(context.getBean(Flyway.class).getConfiguration().getLocations())
                    .extracting(Object::toString)
                    .containsExactly("classpath:db/migration");
        });
    }
}
