package com.stockflow.identity;

import com.stockflow.identity.adapter.in.bootstrap.AdminBootstrapApplication;
import com.stockflow.identity.application.AdminBootstrapException;
import com.stockflow.identity.application.BootstrapAdmin;
import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.support.SharedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T04-10 y T04-11: el bootstrap es un proceso aparte. Se arranca aquí exactamente como en producción, solo con el
 * datasource y sus tres variables: sin secreto JWT, CORS, URLs públicas, SMTP ni clave del outbox.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapIT {

    private static final String EMAIL = "admin@example.test";
    private static final String PASSWORD = "B00tstrap-Passw0rd";

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            SharedPostgres.CONTAINER.getJdbcUrl(), SharedPostgres.CONTAINER.getUsername(), SharedPostgres.CONTAINER.getPassword()));
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanDatabase() {
        // El primer arranque aplica Flyway; después se limpian los datos para cada caso.
        try (var ignored = start(false, null, null)) {
            jdbc.update("DELETE FROM outbound_emails");
            jdbc.update("DELETE FROM one_time_tokens");
            jdbc.update("DELETE FROM auth_sessions");
            jdbc.update("DELETE FROM users");
        }
    }

    // ---- helpers ----

    private static ConfigurableApplicationContext start(boolean enabled, String email, String password) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", SharedPostgres.CONTAINER.getJdbcUrl());
        properties.put("spring.datasource.username", SharedPostgres.CONTAINER.getUsername());
        properties.put("spring.datasource.password", SharedPostgres.CONTAINER.getPassword());
        properties.put("stockflow.admin-bootstrap.enabled", Boolean.toString(enabled));
        if (email != null) {
            properties.put("stockflow.admin-bootstrap.email", email);
        }
        if (password != null) {
            properties.put("stockflow.admin-bootstrap.password", password);
        }
        properties.put("spring.main.banner-mode", "off");
        return AdminBootstrapApplication.start(properties.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }

    private static void runBootstrap(String email, String password) {
        try (var ignored = start(true, email, password)) {
            // el runner ya ejecutó el bootstrap al arrancar
        }
    }

    private UUID insertUser(String email, String role, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                                   failed_login_attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 2, now(), now())
                """, id, email, bcrypt.encode("Old-Passw0rd"), role, status,
                "PENDING_ACTIVATION".equals(status) ? null : OffsetDateTime.now());
        return id;
    }

    private String snapshot() {
        return jdbc.queryForList("SELECT * FROM users ORDER BY id").toString()
                + jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY jti")
                + jdbc.queryForList("SELECT * FROM one_time_tokens ORDER BY id");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---- T04-10 ----

    @Test
    void withTheBootstrapDisabledNothingIsCreatedEvenWithCredentials() {
        String before = snapshot();

        try (var context = start(false, EMAIL, PASSWORD)) {
            assertThat(context.getBeanNamesForType(org.springframework.boot.ApplicationRunner.class)).isEmpty();
        }

        assertThat(snapshot()).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM users")).isZero();
    }

    @Test
    void invalidOrMissingInputFailsSafelyWithoutMutatingAnything(CapturedOutput output) {
        String before = snapshot();

        for (String[] bad : new String[][]{
                {null, PASSWORD}, {"", PASSWORD}, {"not-an-email", PASSWORD}, {EMAIL, null}, {EMAIL, "short1"},
                {EMAIL, "onlyletters"}, {EMAIL, "12345678"}, {EMAIL, "a".repeat(70) + "12345"}}) {
            assertThatThrownBy(() -> runBootstrap(bad[0], bad[1])).as(String.valueOf(bad[0]) + "/" + bad[1])
                    .satisfies(e -> assertThat(rootOf(e)).isInstanceOf(AdminBootstrapException.class)
                            .hasMessageNotContaining(PASSWORD).hasMessageNotContaining("not-an-email"));
        }

        assertThat(snapshot()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain("onlyletters").doesNotContain("12345678");
    }

    @Test
    void createsTheFirstAdminWhenNoneExists(CapturedOutput output) {
        runBootstrap("  Admin@Example.TEST ", PASSWORD);

        Map<String, Object> admin = jdbc.queryForMap("SELECT * FROM users");
        assertThat(admin.get("email_normalized")).isEqualTo(EMAIL);
        assertThat(admin.get("role")).isEqualTo("ADMIN");
        assertThat(admin.get("account_status")).isEqualTo("ACTIVE");
        assertThat(admin.get("activation_completed_at")).isNotNull();
        assertThat(admin.get("password_reset_required")).isEqualTo(false);
        assertThat(admin.get("failed_login_attempts")).isEqualTo(0);
        assertThat(admin.get("locked_until")).isNull();
        String hash = (String) admin.get("password_hash");
        assertThat(hash).startsWith("$2");
        assertThat(bcrypt.matches(PASSWORD, hash)).isTrue();

        String logs = output.getAll();
        assertThat(logs).contains("Admin bootstrap finished: created")
                .doesNotContain(PASSWORD).doesNotContain(hash).doesNotContain(EMAIL).doesNotContain("Admin@Example.TEST");
    }

    @Test
    void promotesAnActiveStandardUserReplacingItsPasswordAndRevokingSessionsAndResetTokens(CapturedOutput output) {
        UUID id = insertUser(EMAIL, "STANDARD", "ACTIVE");
        jdbc.update("UPDATE users SET password_reset_required = true WHERE id = ?", id);
        jdbc.update("INSERT INTO auth_sessions (jti, user_id, issued_at, expires_at) VALUES (?, ?, now(), now() + interval '1 hour')",
                UUID.randomUUID(), id);
        jdbc.update("""
                INSERT INTO one_time_tokens (id, user_id, purpose, token_hash, expires_at)
                VALUES (?, ?, 'PASSWORD_RESET', ?, now() + interval '1 hour')
                """, UUID.randomUUID(), id, "a".repeat(64));

        runBootstrap(EMAIL, PASSWORD);

        Map<String, Object> admin = jdbc.queryForMap("SELECT * FROM users");
        assertThat(admin.get("role")).isEqualTo("ADMIN");
        assertThat(admin.get("password_reset_required")).isEqualTo(false);
        assertThat(bcrypt.matches(PASSWORD, (String) admin.get("password_hash"))).isTrue();
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE invalidated_at IS NULL AND consumed_at IS NULL")).isZero();
        assertThat(output.getAll()).contains("Admin bootstrap finished: promoted").doesNotContain(PASSWORD);
    }

    @Test
    void repeatingWithTheSameEmailIsANoOpThatNeitherRehashesNorRevokes(CapturedOutput output) {
        runBootstrap(EMAIL, PASSWORD);
        UUID id = jdbc.queryForObject("SELECT id FROM users", UUID.class);
        jdbc.update("INSERT INTO auth_sessions (jti, user_id, issued_at, expires_at) VALUES (?, ?, now(), now() + interval '1 hour')",
                UUID.randomUUID(), id);
        String before = snapshot();

        runBootstrap(EMAIL, "Different-Passw0rd");

        assertThat(snapshot()).isEqualTo(before);
        assertThat(output.getAll()).contains("Admin bootstrap finished: already-present");
    }

    @Test
    void anotherEmailFailsWhenAnAdminAlreadyExists() {
        insertUser("existing@example.test", "ADMIN", "ACTIVE");
        insertUser(EMAIL, "STANDARD", "ACTIVE");
        String before = snapshot();

        assertThatThrownBy(() -> runBootstrap(EMAIL, PASSWORD)).satisfies(AdminBootstrapIT::isBootstrapFailure);
        assertThatThrownBy(() -> runBootstrap("new@example.test", PASSWORD)).satisfies(AdminBootstrapIT::isBootstrapFailure);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void pendingOrDisabledAccountsAreNeverPromoted() {
        insertUser(EMAIL, "STANDARD", "PENDING_ACTIVATION");
        insertUser("disabled@example.test", "STANDARD", "DISABLED");
        String before = snapshot();

        assertThatThrownBy(() -> runBootstrap(EMAIL, PASSWORD)).satisfies(AdminBootstrapIT::isBootstrapFailure);
        assertThatThrownBy(() -> runBootstrap("disabled@example.test", PASSWORD)).satisfies(AdminBootstrapIT::isBootstrapFailure);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void concurrentBootstrapsCreateOrPromoteAtMostOneAdmin() throws Exception {
        try (var context = start(false, null, null)) {
            BootstrapAdmin bootstrap = context.getBean(BootstrapAdmin.class);
            UserAdministrationRepository admins = context.getBean(UserAdministrationRepository.class);
            PlatformTransactionManager transactions = context.getBean(PlatformTransactionManager.class);
            insertUser("standard@example.test", "STANDARD", "ACTIVE");

            ExecutorService pool = Executors.newFixedThreadPool(3);
            List<Object> results = new ArrayList<>();
            try {
                List<Future<Object>> futures = new TransactionTemplate(transactions).execute(tx -> {
                    admins.acquireAdminMembershipLock(); // los tres bootstraps esperan este mismo bloqueo
                    List<Future<Object>> submitted = List.of(
                            pool.submit(() -> attempt(bootstrap, "first@example.test")),
                            pool.submit(() -> attempt(bootstrap, "second@example.test")),
                            pool.submit(() -> attempt(bootstrap, "standard@example.test")));
                    awaitLockWaiters(3);
                    return submitted;
                });
                for (Future<Object> f : futures) {
                    results.add(f.get(20, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(results).filteredOn(r -> r instanceof BootstrapAdmin.Outcome).hasSize(1);
            assertThat(results).filteredOn(r -> r instanceof AdminBootstrapException).hasSize(2);
            assertThat(count("SELECT count(*) FROM users WHERE role = 'ADMIN'")).isEqualTo(1);
        }
    }

    /** Spring Boot puede relanzar la excepción del runner tal cual o envuelta: se mira la causa más profunda. */
    private static Throwable rootOf(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void isBootstrapFailure(Throwable e) {
        assertThat(rootOf(e)).isInstanceOf(AdminBootstrapException.class);
    }

    private static Object attempt(BootstrapAdmin bootstrap, String email) {
        try {
            return bootstrap.bootstrap(email, PASSWORD);
        } catch (AdminBootstrapException e) {
            return e;
        }
    }

    private void awaitLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try {
            while (System.nanoTime() < deadline) {
                if (count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock'") >= expected) {
                    return;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("Expected " + expected + " bootstrap(s) waiting on the advisory lock");
    }

    // ---- T04-11 ----

    @Test
    void theBootstrapProcessNeedsNoWebJwtCorsUrlsSmtpOrOutboxConfigurationAndStartsNoWebServer() {
        try (var context = start(true, EMAIL, PASSWORD)) {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            for (String type : new String[]{
                    "com.stockflow.identity.adapter.out.security.JwtAccessTokenService",
                    "com.stockflow.notification.application.OutboxPayloadCipher",
                    "com.stockflow.notification.application.OutboxDispatcher",
                    "com.stockflow.shared.config.SecurityConfig",
                    "org.springframework.security.web.SecurityFilterChain",
                    "org.springframework.mail.javamail.JavaMailSender"}) {
                assertThat(beansOf(context, type)).as(type).isZero();
            }
        }
        assertThat(count("SELECT count(*) FROM users WHERE role = 'ADMIN'")).isEqualTo(1);
    }

    private static int beansOf(ConfigurableApplicationContext context, String className) {
        try {
            return context.getBeanNamesForType(Class.forName(className)).length;
        } catch (ClassNotFoundException e) {
            return 0;
        }
    }
}
