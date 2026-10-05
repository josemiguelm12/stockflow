package com.stockflow.identity;

import com.stockflow.identity.application.ActivateAccount;
import com.stockflow.identity.application.ResendActivation;
import com.stockflow.notification.application.EncryptedPayload;
import com.stockflow.notification.application.OutboxPayloadCipher;
import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * T01-H01: el ciclo de vida de activación (reenvío y activación) se serializa por usuario con un bloqueo de fila.
 * Los tres primeros casos reproducen de forma determinista la intercalación: una transacción sostiene el bloqueo,
 * la otra debe quedar esperando (se comprueba en pg_stat_activity) y solo avanza cuando la primera confirma.
 */
class ActivationLifecycleConcurrencyIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String EMAIL = "user@example.test";
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

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
    @Autowired
    private ActivateAccount activateAccount;
    @Autowired
    private ResendActivation resendActivation;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
    }

    // ---- casos ----

    @Test
    void twoResendsWaitingOnTheUserLockLeaveExactlyOneUsableToken() throws Exception {
        register(EMAIL);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> resends = new TransactionTemplate(transactionManager).execute(status -> {
                lockUserRow(EMAIL);
                Future<Integer> first = pool.submit(() -> resend(EMAIL));
                Future<Integer> second = pool.submit(() -> resend(EMAIL));
                awaitLockWaiters(2);
                return List.of(first, second);
            });
            for (Future<Integer> resend : resends) {
                assertThat(resend.get(10, TimeUnit.SECONDS)).isEqualTo(202);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(usableTokens(EMAIL)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM one_time_tokens")).isEqualTo(3);
        List<String> tokens = pendingTokens(EMAIL);
        assertThat(tokens).hasSize(3);
        List<Integer> statuses = new ArrayList<>();
        for (String token : tokens) {
            statuses.add(activate(token));
        }
        assertThat(statuses).filteredOn(s -> s == 204).hasSize(1);
        assertThat(accountStatus(EMAIL)).isEqualTo("ACTIVE");
    }

    @Test
    void resendWaitingForAnActivationThatCompletesIssuesNoTokenOrEmail() throws Exception {
        register(EMAIL);
        String token = pendingTokens(EMAIL).get(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> resend = new TransactionTemplate(transactionManager).execute(status -> {
                activateAccount.activate(token); // toma el bloqueo del usuario y lo mantiene hasta el commit
                Future<Integer> waiting = pool.submit(() -> resend(EMAIL));
                awaitLockWaiters(1);
                return waiting;
            });
            assertThat(resend.get(10, TimeUnit.SECONDS)).isEqualTo(202);
        } finally {
            pool.shutdownNow();
        }

        assertThat(accountStatus(EMAIL)).isEqualTo("ACTIVE");
        assertThat(usableTokens(EMAIL)).isZero();
        assertThat(count("SELECT count(*) FROM one_time_tokens")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE recipient_email = ?", EMAIL)).isEqualTo(1);
    }

    @Test
    void activationWaitingForAResendFailsBecauseItsTokenWasInvalidated() throws Exception {
        register(EMAIL);
        String oldToken = pendingTokens(EMAIL).get(0);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> activation = new TransactionTemplate(transactionManager).execute(status -> {
                resendActivation.resend(EMAIL); // toma el bloqueo, invalida el token viejo y emite uno nuevo
                Future<Integer> waiting = pool.submit(() -> activate(oldToken));
                awaitLockWaiters(1);
                return waiting;
            });
            assertThat(activation.get(10, TimeUnit.SECONDS)).isEqualTo(400);
        } finally {
            pool.shutdownNow();
        }

        assertThat(accountStatus(EMAIL)).isEqualTo("PENDING_ACTIVATION");
        assertThat(usableTokens(EMAIL)).isEqualTo(1);
        List<String> tokens = pendingTokens(EMAIL);
        assertThat(tokens).hasSize(2);
        String newToken = tokens.stream().filter(t -> !t.equals(oldToken)).findFirst().orElseThrow();
        assertThat(activate(newToken)).isEqualTo(204);
    }

    @Test
    void racingActivationAndResendsAlwaysEndInAConsistentStateWithoutErrors() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 12; round++) {
                String email = "race" + round + "@example.test";
                register(email);
                String token = pendingTokens(email).get(0);

                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> resends = new ArrayList<>();
                Future<Integer> activation = pool.submit(gated(start, () -> activate(token)));
                for (int i = 0; i < 3; i++) {
                    resends.add(pool.submit(gated(start, () -> resend(email))));
                }
                start.countDown();

                assertThat(activation.get(15, TimeUnit.SECONDS)).isIn(204, 400);
                for (Future<Integer> resend : resends) {
                    assertThat(resend.get(15, TimeUnit.SECONDS)).isEqualTo(202);
                }

                if (accountStatus(email).equals("ACTIVE")) {
                    // La activación ganó a todos los reenvíos: ninguno pudo emitir token ni correo.
                    assertThat(usableTokens(email)).as(email).isZero();
                    assertThat(count("SELECT count(*) FROM outbound_emails WHERE recipient_email = ?", email)).as(email).isEqualTo(1);
                } else {
                    // Algún reenvío invalidó el token original: la activación falló y queda un único token utilizable.
                    assertThat(usableTokens(email)).as(email).isEqualTo(1);
                    assertThat(count("SELECT count(*) FROM outbound_emails WHERE recipient_email = ?", email)).as(email).isEqualTo(4);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers ----

    private static <T> Callable<T> gated(CountDownLatch start, Callable<T> action) {
        return () -> {
            start.await();
            return action.call();
        };
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

    /** El reloj está congelado: se avanza un segundo por registro para que enqueued_at sea estrictamente creciente. */
    private void register(String email) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        int status = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(201);
    }

    private int resend(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/resend-activation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn().getResponse().getStatus();
    }

    private int activate(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/activate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")).andReturn().getResponse().getStatus();
    }

    /** Tokens crudos de todos los correos PENDING del destinatario, descifrando el outbox. */
    private List<String> pendingTokens(String email) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT payload_ciphertext, payload_iv, payload_key_id FROM outbound_emails
                WHERE recipient_email = ? AND state = 'PENDING' ORDER BY enqueued_at, id
                """, email);
        List<String> tokens = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            byte[] plaintext = cipher.decrypt(new EncryptedPayload((byte[]) row.get("payload_ciphertext"),
                    (byte[]) row.get("payload_iv"), (String) row.get("payload_key_id")));
            Matcher matcher = TOKEN.matcher(new String(plaintext, StandardCharsets.UTF_8));
            assertThat(matcher.find()).isTrue();
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    private int usableTokens(String email) {
        return count("""
                SELECT count(*) FROM one_time_tokens t JOIN users u ON u.id = t.user_id
                WHERE u.email_normalized = ? AND t.consumed_at IS NULL AND t.invalidated_at IS NULL AND t.expires_at > ?
                """, email, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private String accountStatus(String email) {
        return jdbc.queryForObject("SELECT account_status FROM users WHERE email_normalized = ?", String.class, email);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
