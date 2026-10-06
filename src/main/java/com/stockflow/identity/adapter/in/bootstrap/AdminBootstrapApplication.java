package com.stockflow.identity.adapter.in.bootstrap;

import com.stockflow.identity.application.BootstrapAdmin;
import com.stockflow.identity.application.PasswordHasher;
import com.stockflow.identity.application.PasswordResetTokenRepository;
import com.stockflow.identity.application.SessionRepository;
import com.stockflow.identity.application.UserAdministrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.time.Clock;

/**
 * Proceso de bootstrap del primer ADMIN (no es un endpoint HTTP). Contexto mínimo: datasource/Flyway por
 * autoconfiguración, los repositorios JDBC y BCrypt; nada de web, JWT, CORS, URLs públicas, SMTP ni outbox, así
 * que no exige esa configuración. Ejecuta el caso de uso una vez y termina.
 *
 * <p>Deliberadamente sin {@code @Configuration}/{@code @SpringBootApplication}: así el escaneo de componentes de
 * la aplicación web no lo recoge, y las pruebas {@code @SpringBootTest} no lo confunden con la configuración principal.
 */
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "com.stockflow.identity.adapter.out.persistence",
        "com.stockflow.identity.adapter.out.security"})
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AdminBootstrapApplication {

    static final String ENABLED_PROPERTY = "stockflow.admin-bootstrap.enabled";
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapApplication.class);

    /**
     * ¿Se pidió el modo bootstrap? Lee {@code STOCKFLOW_ADMIN_BOOTSTRAP_ENABLED} (entorno), la propiedad de sistema
     * o el argumento {@code --stockflow.admin-bootstrap.enabled=true}. Por defecto, no.
     */
    public static boolean isRequested(String[] args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        return Boolean.parseBoolean(environment.getProperty(ENABLED_PROPERTY, "false").trim());
    }

    /** Arranca el contexto (el runner hace el bootstrap) y lo cierra; el proceso termina después. */
    public static void run(String[] args) {
        try (ConfigurableApplicationContext ignored = start(args)) {
            log.debug("Admin bootstrap context closed");
        }
    }

    public static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(AdminBootstrapApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Bean
    Clock adminBootstrapClock() {
        return Clock.systemUTC();
    }

    @Bean
    BootstrapAdmin bootstrapAdmin(UserAdministrationRepository admins, PasswordResetTokenRepository resetTokens,
                                  SessionRepository sessions, PasswordHasher hasher, Clock clock) {
        return new BootstrapAdmin(admins, resetTokens, sessions, hasher, clock);
    }

    /** Solo con el bootstrap habilitado; con él deshabilitado no se crea ni modifica ningún usuario. */
    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true")
    ApplicationRunner adminBootstrapRunner(BootstrapAdmin bootstrapAdmin, AdminBootstrapProperties properties) {
        return args -> {
            BootstrapAdmin.Outcome outcome = bootstrapAdmin.bootstrap(properties.email(), properties.password());
            // Resultado seguro: nunca email, contraseña ni hash.
            log.info("Admin bootstrap finished: {}", outcome.label());
        };
    }
}
