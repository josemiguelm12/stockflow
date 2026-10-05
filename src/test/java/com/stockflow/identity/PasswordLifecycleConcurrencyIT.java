package com.stockflow.identity;

import com.stockflow.notification.application.EncryptedPayload;
import com.stockflow.notification.application.OutboxPayloadCipher;
import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T03-03 y T03-06: el ciclo de vida de recuperación se serializa por usuario con un bloqueo de fila (primero el
 * usuario, luego tokens, sesiones y outbox). Los casos deterministas sostienen el bloqueo en una transacción,
 * comprueban en pg_stat_activity que la otra espera de verdad, y solo entonces confirman.
 */
class PasswordLifecycleConcurrencyIT extends AbstractPostgresIT {

    private static final String OLD = "Passw0rd-secret";
    private static final String NEW = "N3wPassw0rd-ok";
    private static final String EMAIL = "user@example.test";
    private static final Pattern PAYLOAD_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OutboxPayloadCipher cipher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
    }

    // ---- casos ----

    @Test
    void twoForgotRequestsWaitingOnTheUserLockLeaveExactlyOneUsableToken() throws Exception {
        createUser(EMAIL);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> requests = new TransactionTemplate(transactionManager).execute(status -> {
                lockUserRow(EMAIL);
                Future<Integer> first = pool.submit(() -> forgot(EMAIL));
                Future<Integer> second = pool.submit(() -> forgot(EMAIL));
                awaitLockWaiters(2);
                return List.of(first, second);
            });
            for (Future<Integer> request : requests) {
                assertThat(request.get(10, TimeUnit.SECONDS)).isEqualTo(202);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(usableResetTokens()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE purpose = 'PASSWORD_RESET'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE template_key = 'PASSWORD_RESET'")).isEqualTo(2);
        List<String> tokens = emailedTokens(EMAIL);
        List<Integer> statuses = new ArrayList<>();
        for (String token : tokens) {
            statuses.add(reset(token, NEW));
        }
        assertThat(statuses).filteredOn(s -> s == 204).hasSize(1);
    }

    @Test
    void manyConcurrentForgotRequestsNeverLeaveMoreThanOneUsableToken() throws Exception {
        createUser(EMAIL);

        List<Callable<Integer>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(() -> forgot(EMAIL));
        }
        assertThat(runConcurrently(requests)).containsOnly(202);

        assertThat(usableResetTokens()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE purpose = 'PASSWORD_RESET'")).isEqualTo(8);
        assertThat(count("SELECT count(*) FROM outbound_emails")).isEqualTo(8);
        int successes = 0;
        for (String token : emailedTokens(EMAIL)) {
            if (reset(token, NEW) == 204) {
                successes++;
            }
        }
        assertThat(successes).isEqualTo(1);
    }

    @Test
    void concurrentRedemptionsOfTheSameTokenSucceedAtMostOnce() throws Exception {
        createUser(EMAIL);
        String session = loginToken(EMAIL, OLD);
        forgot(EMAIL);
        String token = emailedTokens(EMAIL).get(0);

        List<Callable<Integer>> redemptions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            redemptions.add(() -> reset(token, NEW));
        }
        List<Integer> statuses = runConcurrently(redemptions);

        assertThat(statuses).filteredOn(s -> s == 204).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 400).hasSize(7);
        assertThat(bcrypt.matches(NEW, passwordHash(EMAIL))).isTrue();
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE consumed_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL")).isZero();
        assertThat(session).isNotBlank();
    }

    @Test
    void aResetWaitingBehindAnAccountThatGetsDisabledFailsWithoutConsumingTheToken() throws Exception {
        createUser(EMAIL);
        forgot(EMAIL);
        String token = emailedTokens(EMAIL).get(0);
        String hashBefore = passwordHash(EMAIL);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> waiting = new TransactionTemplate(transactionManager).execute(status -> {
                lockUserRow(EMAIL);
                Future<Integer> reset = pool.submit(() -> reset(token, NEW));
                awaitLockWaiters(1);
                jdbc.update("UPDATE users SET account_status = 'DISABLED' WHERE email_normalized = ?", EMAIL);
                return reset;
            });
            assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(400);
        } finally {
            pool.shutdownNow();
        }

        assertThat(passwordHash(EMAIL)).isEqualTo(hashBefore);
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE consumed_at IS NOT NULL")).isZero();
    }

    @Test
    void aForgotWaitingBehindAnAccountThatGetsDisabledIssuesNothing() throws Exception {
        createUser(EMAIL);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> waiting = new TransactionTemplate(transactionManager).execute(status -> {
                lockUserRow(EMAIL);
                Future<Integer> forgot = pool.submit(() -> forgot(EMAIL));
                awaitLockWaiters(1);
                jdbc.update("UPDATE users SET account_status = 'DISABLED' WHERE email_normalized = ?", EMAIL);
                return forgot;
            });
            assertThat(waiting.get(10, TimeUnit.SECONDS)).isEqualTo(202);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM one_time_tokens")).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();
    }

    @Test
    void aChangeWaitingForAResetEndsUpWithAConsistentPasswordAndNoLiveSession() throws Exception {
        createUser(EMAIL);
        String bearer = loginToken(EMAIL, OLD);
        forgot(EMAIL);
        String token = emailedTokens(EMAIL).get(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> change = new TransactionTemplate(transactionManager).execute(status -> {
                // El reset real mantiene el bloqueo del usuario hasta el commit; el cambio espera detrás.
                assertThat(resetInTransaction(token)).isNotNull();
                Future<Integer> waiting = pool.submit(() -> change(bearer, OLD, "S3cond-Passw0rd"));
                awaitLockWaiters(1);
                return waiting;
            });
            // El Bearer ya estaba autenticado cuando el cambio quedó esperando el bloqueo. Al reanudarse ve el hash que
            // dejó el reset: la contraseña actual (OLD) ya no coincide, así que se rechaza con el 400 genérico.
            assertThat(change.get(10, TimeUnit.SECONDS)).isEqualTo(400);
        } finally {
            pool.shutdownNow();
        }

        assertThat(bcrypt.matches(NEW, passwordHash(EMAIL))).isTrue();
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL")).isZero();
    }

    @Test
    void aLoginRacingAResetNeverLeavesALiveSessionWithTheOldPassword() throws Exception {
        for (int round = 0; round < 8; round++) {
            String email = "race" + round + "@example.test";
            createUser(email);
            forgot(email);
            String token = emailedTokens(email).get(0);

            List<Callable<Object>> both = List.of(
                    () -> loginResult(email, OLD),
                    () -> reset(token, NEW));
            List<Object> results = runConcurrently(both);

            assertThat(results.get(1)).as(email).isEqualTo(204);
            // Si el login ganó la carrera, su sesión fue revocada por el reset; si perdió, el login falló.
            assertThat(count("SELECT count(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id "
                    + "WHERE u.email_normalized = ? AND s.revoked_at IS NULL", email)).as(email).isZero();
            Object login = results.get(0);
            if (login instanceof String accessToken) {
                assertThat(meStatus(accessToken)).as(email).isEqualTo(401);
            }
            assertThat(bcrypt.matches(NEW, passwordHash(email))).isTrue();
        }
    }

    // ---- helpers ----

    private UUID createUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
                VALUES (?, ?, ?, 'STANDARD', 'ACTIVE', now(), now())
                """, id, email, bcrypt.encode(OLD));
        return id;
    }

    private void lockUserRow(String email) {
        jdbc.queryForObject("SELECT id FROM users WHERE email_normalized = ? FOR UPDATE", UUID.class, email);
    }

    /** Espera a que haya transacciones bloqueadas por un bloqueo de fila; si no las hay, el ciclo no está serializado. */
    private void awaitLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (System.nanoTime() < deadline) {
                int waiting = count("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock'");
                if (waiting >= expected) {
                    return;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("Expected " + expected + " transaction(s) waiting on the user row lock");
    }

    /** El reloj está congelado: se avanza un segundo por solicitud para que enqueued_at sea estrictamente creciente. */
    private int forgot(String email) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        return mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn().getResponse().getStatus();
    }

    private int reset(String token, String newPassword) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    /** Ejecuta el reset real dentro de la transacción del llamador: la fila del usuario queda bloqueada hasta el commit. */
    private Integer resetInTransaction(String token) {
        resetPassword.reset(token, NEW);
        return 204;
    }

    @Autowired
    private com.stockflow.identity.application.ResetPassword resetPassword;

    private int change(String bearer, String current, String newPassword) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/change").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private String loginToken(String email, String password) throws Exception {
        Object result = loginResult(email, password);
        assertThat(result).isInstanceOf(String.class);
        return (String) result;
    }

    /** Token de acceso si el login fue 200; el código HTTP (Integer) en otro caso. */
    private Object loginResult(String email, String password) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse();
        if (response.getStatus() != 200) {
            return response.getStatus();
        }
        Matcher m = ACCESS_TOKEN.matcher(response.getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private int meStatus(String token) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    /** Tokens crudos de todos los correos de recuperación PENDING del destinatario (descifra el outbox). */
    private List<String> emailedTokens(String email) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT payload_ciphertext, payload_iv, payload_key_id FROM outbound_emails
                WHERE recipient_email = ? AND template_key = 'PASSWORD_RESET' AND state = 'PENDING'
                ORDER BY enqueued_at, id
                """, email);
        List<String> tokens = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            byte[] plaintext = cipher.decrypt(new EncryptedPayload((byte[]) row.get("payload_ciphertext"),
                    (byte[]) row.get("payload_iv"), (String) row.get("payload_key_id")));
            Matcher m = PAYLOAD_TOKEN.matcher(new String(plaintext, StandardCharsets.UTF_8));
            assertThat(m.find()).isTrue();
            tokens.add(m.group(1));
        }
        return tokens;
    }

    private String passwordHash(String email) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE email_normalized = ?", String.class, email);
    }

    private int usableResetTokens() {
        return count("""
                SELECT count(*) FROM one_time_tokens WHERE purpose = 'PASSWORD_RESET'
                AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > ?
                """, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private <T> List<T> runConcurrently(List<? extends Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
