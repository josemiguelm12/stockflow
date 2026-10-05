package com.stockflow.notification;

import com.stockflow.StockflowApplication;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.Login;
import com.stockflow.identity.application.Logout;
import com.stockflow.notification.application.OutboxDispatcher;
import com.stockflow.support.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El worker SMTP arranca como proceso aparte: sin servidor web, sin los casos de uso de sesión y, por tanto,
 * sin STOCKFLOW_JWT_SECRET ni rate limiting. Regresión: AuthController exigía el bean Login también en este modo.
 */
class WorkerModeIT {

    @Test
    void theWorkerStartsWithoutWebBeansAndWithoutAJwtSecret() {
        var postgres = SharedPostgres.CONTAINER;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(StockflowApplication.class)
                .web(WebApplicationType.NONE)
                .run(arguments(Map.ofEntries(
                        Map.entry("spring.datasource.url", postgres.getJdbcUrl()),
                        Map.entry("spring.datasource.username", postgres.getUsername()),
                        Map.entry("spring.datasource.password", postgres.getPassword()),
                        Map.entry("stockflow.cors.allowed-origins", "http://localhost:4200"),
                        Map.entry("stockflow.activation.public-url", "http://localhost:8080/activate"),
                        Map.entry("stockflow.activation.token-ttl", "PT24H"),
                        Map.entry("stockflow.password-reset.public-url", "http://localhost:8080/reset-password"),
                        Map.entry("stockflow.outbox.encryption-key", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
                        Map.entry("stockflow.outbox.encryption-key-id", "test-key-1"),
                        Map.entry("stockflow.smtp.host", "127.0.0.1"),
                        Map.entry("stockflow.smtp.port", "1"),
                        Map.entry("stockflow.smtp.from", "noreply@stockflow.test"),
                        Map.entry("stockflow.worker.enabled", "true"),
                        // Sin secreto JWT: el worker no debe necesitarlo.
                        Map.entry("stockflow.jwt.secret", ""),
                        Map.entry("spring.main.banner-mode", "off"),
                        Map.entry("logging.level.root", "WARN"))))) {

            assertThat(context.getBeansOfType(OutboxDispatcher.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(Login.class)).isEmpty();
            assertThat(context.getBeanNamesForType(Logout.class)).isEmpty();
            assertThat(context.getBeanNamesForType(AuthenticateSession.class)).isEmpty();
            assertThat(context.containsBean("authController")).isFalse();
            assertThat(context.containsBean("passwordController")).isFalse();
        }
    }

    /** Argumentos --clave=valor: tienen la máxima precedencia y no se pierden como las propiedades por defecto. */
    @SafeVarargs
    private static String[] arguments(Map<String, String>... maps) {
        return java.util.Arrays.stream(maps)
                .flatMap(m -> m.entrySet().stream())
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
    }
}
