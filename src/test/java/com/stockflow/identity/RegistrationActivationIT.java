package com.stockflow.identity;

import com.stockflow.notification.application.EncryptedPayload;
import com.stockflow.notification.application.OutboxDispatcher;
import com.stockflow.notification.application.OutboxPayloadCipher;
import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import com.stockflow.support.RecordingEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RegistrationActivationIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private RecordingEmailSender smtp;
    @Autowired
    private OutboxDispatcher worker;
    @Autowired
    private OutboxPayloadCipher cipher;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
        smtp.reset();
    }

    // ---- helpers ----

    /** El reloj está congelado: se avanza un segundo por operación para que enqueued_at sea estrictamente creciente. */
    private MvcResult register(String email, String password) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", password))).andReturn();
    }

    private int activate(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/activate").contentType(MediaType.APPLICATION_JSON)
                .content(json("token", token))).andReturn().getResponse().getStatus();
    }

    private MvcResult resend(String email) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        return mvc.perform(post("/api/v1/auth/resend-activation").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email))).andReturn();
    }

    private static String json(String... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            sb.append(i > 0 ? "," : "").append('"').append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    /** Descifra el payload del correo PENDING más reciente para recuperar el token que viajaría por SMTP. */
    private String latestPendingToken(String email) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT payload_ciphertext, payload_iv, payload_key_id FROM outbound_emails
                WHERE recipient_email = ? AND state = 'PENDING' ORDER BY enqueued_at DESC, id LIMIT 1
                """, email);
        byte[] plaintext = cipher.decrypt(new EncryptedPayload(
                (byte[]) row.get("payload_ciphertext"), (byte[]) row.get("payload_iv"), (String) row.get("payload_key_id")));
        Matcher m = TOKEN.matcher(new String(plaintext, StandardCharsets.UTF_8));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String accountStatus(String email) {
        return jdbc.queryForObject("SELECT account_status FROM users WHERE email_normalized = ?", String.class, email);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ---- T01-01 ----

    @Test
    void registrationCreatesPendingUserWithSaltedHashAndEncryptedOutbox() throws Exception {
        MvcResult result = register("user@example.test", PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users WHERE email_normalized = 'user@example.test'");
        assertThat(user.get("role")).isEqualTo("STANDARD");
        assertThat(user.get("account_status")).isEqualTo("PENDING_ACTIVATION");
        assertThat(user.get("activation_completed_at")).isNull();
        String hash = (String) user.get("password_hash");
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(new BCryptPasswordEncoder().matches(PASSWORD, hash)).isTrue();

        Map<String, Object> mail = jdbc.queryForMap("SELECT * FROM outbound_emails");
        assertThat(mail.get("state")).isEqualTo("PENDING");
        assertThat(mail.get("recipient_email")).isEqualTo("user@example.test");
        assertThat((byte[]) mail.get("payload_ciphertext")).isNotEmpty();
        assertThat((byte[]) mail.get("payload_iv")).hasSize(12);
        assertThat(mail.get("payload_key_id")).isEqualTo("test-key-1");

        String token = latestPendingToken("user@example.test");
        String ciphertextText = new String((byte[]) mail.get("payload_ciphertext"), StandardCharsets.ISO_8859_1);
        assertThat(ciphertextText).doesNotContain(token);
        Map<String, Object> stored = jdbc.queryForMap("SELECT * FROM one_time_tokens");
        assertThat(stored.get("purpose")).isEqualTo("ACTIVATION");
        assertThat((String) stored.get("token_hash")).hasSize(64).isNotEqualTo(token);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token).doesNotContain(hash);
    }

    @Test
    void samePasswordForTwoUsersProducesDifferentHashes() throws Exception {
        register("a@example.test", PASSWORD);
        register("b@example.test", PASSWORD);

        List<String> hashes = jdbc.queryForList("SELECT password_hash FROM users", String.class);
        assertThat(hashes).hasSize(2).doesNotHaveDuplicates();
    }

    // ---- T01-02 ----

    @Test
    void duplicateEmailIsRejectedEvenWithSpacesAndUppercase() throws Exception {
        assertThat(register("user@example.test", PASSWORD).getResponse().getStatus()).isEqualTo(201);

        assertThat(register("  USER@Example.TEST ", PASSWORD).getResponse().getStatus()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbound_emails")).isEqualTo(1);
    }

    @Test
    void invalidEmailOrPasswordIs400AndCreatesNothing() throws Exception {
        for (String[] bad : new String[][]{
                {"not-an-email", PASSWORD}, {"user@example.test", "short1"},
                {"user@example.test", "onlyletters"}, {"user@example.test", "12345678"}}) {
            MvcResult result = register(bad[0], bad[1]);
            assertThat(result.getResponse().getStatus()).as(bad[0] + "/" + bad[1]).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).doesNotContain(bad[1]);
        }
        assertThat(count("SELECT count(*) FROM users")).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();
    }

    @Test
    void registrationCannotChooseRoleOrStatus() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.test\",\"password\":\"" + PASSWORD
                                + "\",\"role\":\"ADMIN\",\"accountStatus\":\"ACTIVE\"}"))
                .andExpect(status().isCreated());

        Map<String, Object> user = jdbc.queryForMap("SELECT role, account_status FROM users");
        assertThat(user.get("role")).isEqualTo("STANDARD");
        assertThat(user.get("account_status")).isEqualTo("PENDING_ACTIVATION");
    }

    // ---- T01-03 ----

    @Test
    void validActivationActivatesOnceAndReuseFailsWithoutMutating() throws Exception {
        register("user@example.test", PASSWORD);
        String token = latestPendingToken("user@example.test");

        assertThat(activate(token)).isEqualTo(204);
        assertThat(accountStatus("user@example.test")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT activation_completed_at FROM users", java.time.OffsetDateTime.class))
                .isNotNull();

        Object completedAt = jdbc.queryForObject("SELECT updated_at FROM users", java.time.OffsetDateTime.class);
        clock.advance(Duration.ofMinutes(5));
        assertThat(activate(token)).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM users", java.time.OffsetDateTime.class))
                .isEqualTo(completedAt);
    }

    @Test
    void expiredTokenIsRejectedAndTheAccountStaysPending() throws Exception {
        register("user@example.test", PASSWORD);
        String token = latestPendingToken("user@example.test");

        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(activate(token)).isEqualTo(400);
        assertThat(accountStatus("user@example.test")).isEqualTo("PENDING_ACTIVATION");
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE consumed_at IS NOT NULL")).isZero();
    }

    @Test
    void unknownOrBlankTokenIs400() throws Exception {
        assertThat(activate("does-not-exist")).isEqualTo(400);
        assertThat(activate("")).isEqualTo(400);
    }

    @Test
    void concurrentActivationsWithTheSameTokenSucceedOnlyOnce() throws Exception {
        register("user@example.test", PASSWORD);
        String token = latestPendingToken("user@example.test");

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> attempts = java.util.Collections.nCopies(6, () -> activate(token));
            int successes = 0;
            for (Future<Integer> f : pool.invokeAll(attempts)) {
                if (f.get() == 204) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accountStatus("user@example.test")).isEqualTo("ACTIVE");
    }

    // ---- T01-04 ----

    @Test
    void resendRespondsTheSameForExistingAndUnknownAccountsAndInvalidatesThePreviousToken() throws Exception {
        register("user@example.test", PASSWORD);
        String oldToken = latestPendingToken("user@example.test");

        MvcResult existing = resend("user@example.test");
        MvcResult unknown = resend("nobody@example.test");

        assertThat(existing.getResponse().getStatus()).isEqualTo(202);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(202);
        assertThat(existing.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE recipient_email = 'nobody@example.test'")).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE recipient_email = 'user@example.test'")).isEqualTo(2);

        assertThat(activate(oldToken)).isEqualTo(400);
        assertThat(accountStatus("user@example.test")).isEqualTo("PENDING_ACTIVATION");

        String newToken = latestPendingToken("user@example.test");
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(activate(newToken)).isEqualTo(204);
    }

    @Test
    void resendNormalizesTheEmailAndSkipsAlreadyActiveAccounts() throws Exception {
        register("user@example.test", PASSWORD);

        assertThat(resend("  USER@example.TEST ").getResponse().getStatus()).isEqualTo(202);
        assertThat(count("SELECT count(*) FROM outbound_emails")).isEqualTo(2);

        assertThat(activate(latestPendingToken("user@example.test"))).isEqualTo(204);
        assertThat(resend("user@example.test").getResponse().getStatus()).isEqualTo(202);
        assertThat(count("SELECT count(*) FROM outbound_emails")).isEqualTo(2);
    }

    // ---- T01-05 ----

    @Test
    void smtpDownDoesNotRevertRegistrationOrResendAndLeavesEmailsPending() throws Exception {
        smtp.setFailing(true);

        assertThat(register("user@example.test", PASSWORD).getResponse().getStatus()).isEqualTo(201);
        assertThat(resend("user@example.test").getResponse().getStatus()).isEqualTo(202);

        OutboxDispatcher.Result result = worker.dispatchPending(10);

        assertThat(result.sent()).isZero();
        assertThat(result.failed()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE state = 'PENDING' AND payload_ciphertext IS NOT NULL"))
                .isEqualTo(2);

        smtp.setFailing(false);
        assertThat(worker.dispatchPending(10).sent()).isEqualTo(2);
    }

    // ---- T01-06 ----

    @Test
    void workerSendsOnceMarksSentPurgesPayloadAndASecondRunDoesNotResend() throws Exception {
        register("user@example.test", PASSWORD);
        String token = latestPendingToken("user@example.test");

        OutboxDispatcher.Result first = worker.dispatchPending(10);

        assertThat(first.sent()).isEqualTo(1);
        assertThat(smtp.sent()).hasSize(1);
        RecordingEmailSender.Sent mail = smtp.sent().get(0);
        assertThat(mail.recipient()).isEqualTo("user@example.test");
        assertThat(mail.body()).contains("http://localhost:8080/activate#token=" + token);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbound_emails");
        assertThat(row.get("state")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("payload_ciphertext")).isNull();
        assertThat(row.get("payload_iv")).isNull();
        assertThat(row.get("payload_key_id")).isNull();

        assertThat(worker.dispatchPending(10).sent()).isZero();
        assertThat(smtp.sent()).hasSize(1);
        assertThat(activate(token)).isEqualTo(204);
    }

    @Test
    void twoConcurrentWorkersNeverSendTheSameEmailTwice() throws Exception {
        for (int i = 0; i < 4; i++) {
            register("user" + i + "@example.test", PASSWORD);
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<OutboxDispatcher.Result>> runs = List.of(() -> worker.dispatchPending(10), () -> worker.dispatchPending(10));
            for (Future<OutboxDispatcher.Result> f : pool.invokeAll(runs)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(smtp.sent()).hasSize(4);
        assertThat(smtp.sent().stream().map(RecordingEmailSender.Sent::recipient).distinct()).hasSize(4);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE state = 'SENT'")).isEqualTo(4);
    }
}
