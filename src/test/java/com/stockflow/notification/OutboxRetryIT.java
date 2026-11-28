package com.stockflow.notification;

import com.stockflow.notification.application.EmailTemplates;
import com.stockflow.notification.application.OutboundEmailRepository;
import com.stockflow.notification.application.OutboxDispatcher;
import com.stockflow.notification.application.OutboxPayloadCipher;
import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import com.stockflow.support.RecordingEmailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RF-NOT-10, 11 y 12 con reloj controlable: reintentos a 1 y 5 minutos, FAILED terminal, ausencia de reenvíos,
 * consulta administrativa y reclamación concurrente. Sin esperas reales.
 */
class OutboxRetryIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String QUEUE = "/api/v1/admin/outbound-emails";
    private static final String SECRET_TOKEN = "SYNTHETIC-SECRET-TOKEN-8f31";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutboundEmailRepository emails;
    @Autowired
    private OutboxPayloadCipher cipher;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private RecordingEmailSender sender;
    @Autowired
    private MutableClock clock;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @BeforeEach
    void prepare() {
        sender.reset();
    }

    @AfterEach
    void cleanUp() {
        sender.reset();
    }

    // ---- RF-NOT-10 ----

    @Test
    void threeAttemptsAtTheExactLimitsThenFailedForGood() {
        Instant t0 = clock.instant();
        UUID id = enqueue("retry@example.test");
        sender.setFailing(true);

        assertThat(dispatcher.dispatchPending(50)).isEqualTo(new OutboxDispatcher.Result(0, 1));
        Map<String, Object> first = row(id);
        assertThat(first.get("state")).isEqualTo("PENDING");
        assertThat(first.get("attempt_count")).isEqualTo(1);
        assertThat(instant(first, "last_attempt_at")).isEqualTo(t0);
        assertThat(instant(first, "next_attempt_at")).isEqualTo(t0.plus(Duration.ofMinutes(1)));
        assertThat(first.get("last_error")).isEqualTo("SMTP delivery failed (RuntimeException)");
        assertThat(first.get("payload_ciphertext")).isNotNull();

        clock.advance(Duration.ofSeconds(59));
        assertThat(dispatcher.dispatchPending(50)).as("before 1 minute").isEqualTo(new OutboxDispatcher.Result(0, 0));
        assertThat(sender.attempts()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        Instant t1 = clock.instant();
        assertThat(dispatcher.dispatchPending(50)).isEqualTo(new OutboxDispatcher.Result(0, 1));
        Map<String, Object> second = row(id);
        assertThat(second.get("state")).isEqualTo("PENDING");
        assertThat(second.get("attempt_count")).isEqualTo(2);
        assertThat(instant(second, "last_attempt_at")).isEqualTo(t1);
        assertThat(instant(second, "next_attempt_at")).isEqualTo(t1.plus(Duration.ofMinutes(5)));
        assertThat(sender.attempts()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertThat(dispatcher.dispatchPending(50)).as("before 5 more minutes").isEqualTo(new OutboxDispatcher.Result(0, 0));
        assertThat(sender.attempts()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(1));
        Instant t2 = clock.instant();
        assertThat(dispatcher.dispatchPending(50)).isEqualTo(new OutboxDispatcher.Result(0, 1));
        Map<String, Object> last = row(id);
        assertThat(last.get("state")).isEqualTo("FAILED");
        assertThat(last.get("attempt_count")).isEqualTo(3);
        assertThat(instant(last, "failed_at")).isEqualTo(t2);
        assertThat(instant(last, "last_attempt_at")).isEqualTo(t2);
        assertThat(last.get("next_attempt_at")).isNull();
        assertThat(last.get("last_error")).isEqualTo("SMTP delivery failed (RuntimeException)");
        assertThat(last.get("payload_ciphertext")).as("terminal row keeps no ciphertext").isNull();
        assertThat(sender.attempts()).isEqualTo(3);

        // Terminal: ni tras horas ni con SMTP recuperado vuelve a enviarse.
        sender.setFailing(false);
        clock.advance(Duration.ofHours(6));
        assertThat(dispatcher.dispatchPending(50)).isEqualTo(new OutboxDispatcher.Result(0, 0));
        assertThat(sender.attempts()).isEqualTo(3);
        assertThat(sender.sent()).isEmpty();
        assertThat(row(id).get("state")).isEqualTo("FAILED");
    }

    @Test
    void successAfterAFailureIsSentAndNeverRetriedAgain() {
        UUID id = enqueue("later@example.test");
        sender.setFailing(true);
        dispatcher.dispatchPending(50);
        sender.setFailing(false);
        clock.advance(Duration.ofMinutes(1));

        assertThat(dispatcher.dispatchPending(50)).isEqualTo(new OutboxDispatcher.Result(1, 0));

        Map<String, Object> row = row(id);
        assertThat(row.get("state")).isEqualTo("SENT");
        assertThat(row.get("attempt_count")).isEqualTo(2);
        assertThat(instant(row, "sent_at")).isEqualTo(clock.instant());
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("payload_ciphertext")).isNull();

        clock.advance(Duration.ofHours(1));
        assertThat(dispatcher.dispatchPending(50)).isEqualTo(new OutboxDispatcher.Result(0, 0));
        assertThat(sender.sent()).hasSize(1);
        assertThat(sender.attempts()).isEqualTo(2);
    }

    @Test
    void aFirstTrySuccessCountsOneAttempt() {
        UUID id = enqueue("ok@example.test");

        dispatcher.dispatchPending(50);

        assertThat(row(id).get("attempt_count")).isEqualTo(1);
        assertThat(row(id).get("last_error")).isNull();
    }

    @Test
    void theRecordedErrorNeverContainsThePayloadTheTokenOrTheRecipient() {
        UUID id = enqueue("leak-check@example.test");
        sender.setFailing(true);

        dispatcher.dispatchPending(50);

        String error = (String) row(id).get("last_error");
        assertThat(error).isNotBlank().doesNotContain(SECRET_TOKEN).doesNotContain("leak-check")
                .doesNotContain("activate").hasSizeLessThanOrEqualTo(300);
    }

    @Test
    void anUndecryptablePayloadIsRetriedOnTheSameScheduleWithASafeError() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbound_emails (id, recipient_email, template_key, payload_ciphertext, payload_iv,
                                             payload_key_id, state, enqueued_at)
                VALUES (?, 'broken@example.test', 'ACTIVATION', ?, ?, 'test-key-1', 'PENDING', ?)
                """, id, new byte[]{1, 2, 3}, new byte[]{4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

        assertThat(dispatcher.dispatchPending(50).failed()).isEqualTo(1);

        assertThat(row(id).get("state")).isEqualTo("PENDING");
        assertThat((String) row(id).get("last_error")).startsWith("Email processing failed (");
        assertThat(sender.attempts()).isZero();
    }

    @Test
    void aFailedRowKeepsItsDedupeKeyAndTheEventIsNotQueuedAgain() {
        var payload = cipher.encrypt("{\"title\":\"t\",\"message\":\"m\"}".getBytes(StandardCharsets.UTF_8));
        UUID userId = UUID.randomUUID();
        String key = "event-1:" + userId + ":EMAIL";
        assertThat(emails.insertPendingIfAbsent(UUID.randomUUID(), "dup@example.test",
                EmailTemplates.ACTIVATION, payload, clock.instant(), key)).isTrue();
        sender.setFailing(true);
        for (int i = 0; i < 3; i++) {
            dispatcher.dispatchPending(50);
            clock.advance(Duration.ofMinutes(6));
        }
        assertThat(jdbc.queryForObject("SELECT state FROM outbound_emails", String.class)).isEqualTo("FAILED");

        boolean again = emails.insertPendingIfAbsent(UUID.randomUUID(), "dup@example.test",
                EmailTemplates.ACTIVATION, payload, clock.instant(), key);

        assertThat(again).isFalse();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isEqualTo(1);
    }

    @Test
    void aBatchIsBoundedAndEachRunStopsAfterTheMaximum() {
        for (int i = 0; i < 7; i++) {
            enqueue("batch" + i + "@example.test");
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(dispatcher.dispatchPending(5).sent()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE state = 'PENDING'")).isEqualTo(2);
    }

    // ---- RF-NOT-12 / RF-NOT-09: concurrencia ----

    @Test
    void twoConcurrentWorkersSendEachEmailExactlyOnce() throws Exception {
        for (int i = 0; i < 12; i++) {
            enqueue("conc" + i + "@example.test");
            clock.advance(Duration.ofSeconds(1));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<OutboxDispatcher.Result>> runs = List.of(() -> dispatcher.dispatchPending(50),
                    () -> dispatcher.dispatchPending(50));
            int total = 0;
            for (Future<OutboxDispatcher.Result> run : pool.invokeAll(runs)) {
                total += run.get().sent();
            }

            assertThat(total).isEqualTo(12);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sender.sent()).extracting(RecordingEmailSender.Sent::recipient).doesNotHaveDuplicates().hasSize(12);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE state = 'SENT' AND attempt_count = 1"))
                .isEqualTo(12);
    }

    // ---- RF-NOT-11 ----

    @Test
    void anAdminListsDeliveriesWithStatesAttemptsAndDatesAndNeverTheirPayload() throws Exception {
        createUser("q-admin@example.test", "ADMIN");
        String admin = token("q-admin@example.test");
        UUID pending = enqueue("pending@example.test");
        clock.advance(Duration.ofSeconds(1));
        UUID retrying = enqueue("retrying@example.test");
        clock.advance(Duration.ofSeconds(1));
        UUID failed = enqueue("failed@example.test");
        clock.advance(Duration.ofSeconds(1));
        UUID sent = enqueue("sent@example.test");
        jdbc.update("""
                UPDATE outbound_emails SET attempt_count = 1, last_attempt_at = enqueued_at,
                    next_attempt_at = enqueued_at + interval '1 minute', last_error = 'SMTP delivery failed (X)'
                WHERE id = ?
                """, retrying);
        jdbc.update("""
                UPDATE outbound_emails SET state = 'FAILED', attempt_count = 3, last_attempt_at = enqueued_at,
                    failed_at = enqueued_at, last_error = 'SMTP delivery failed (X)',
                    payload_ciphertext = NULL, payload_iv = NULL, payload_key_id = NULL
                WHERE id = ?
                """, failed);
        jdbc.update("""
                UPDATE outbound_emails SET state = 'SENT', attempt_count = 1, last_attempt_at = enqueued_at,
                    sent_at = enqueued_at, payload_ciphertext = NULL, payload_iv = NULL, payload_key_id = NULL
                WHERE id = ?
                """, sent);

        MvcResult all = call(get(QUEUE), admin);
        String body = all.getResponse().getContentAsString();

        assertThat(all.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).contains("\"totalElements\":4").contains("\"state\":\"PENDING\"")
                .contains("\"state\":\"SENT\"").contains("\"state\":\"FAILED\"")
                .contains("\"attemptCount\":3").contains("\"nextAttemptAt\"").contains("\"failedAt\"")
                .contains("\"sentAt\"").contains("\"lastAttemptAt\"").contains("\"enqueuedAt\"")
                .contains("\"lastError\":\"SMTP delivery failed (X)\"");
        assertThat(body).doesNotContain(SECRET_TOKEN).doesNotContainIgnoringCase("payload")
                .doesNotContainIgnoringCase("ciphertext").doesNotContainIgnoringCase("password")
                .doesNotContainIgnoringCase("smtp_");
        // Más recientes primero.
        assertThat(body.indexOf(sent.toString())).isLessThan(body.indexOf(pending.toString()));

        assertThat(call(get(QUEUE + "?state=FAILED"), admin).getResponse().getContentAsString())
                .contains("\"totalElements\":1").contains(failed.toString()).doesNotContain(pending.toString());
        assertThat(call(get(QUEUE + "?state=PENDING"), admin).getResponse().getContentAsString())
                .contains("\"totalElements\":2");
        assertThat(call(get(QUEUE + "?size=1&page=1"), admin).getResponse().getContentAsString())
                .contains("\"totalPages\":4").contains("\"page\":1").contains("\"size\":1");
    }

    @Test
    void theQueueEndpointRejectsStandardAnonymousAndInvalidInput() throws Exception {
        createUser("q-admin@example.test", "ADMIN");
        createUser("q-std@example.test", "STANDARD");
        enqueue("someone@example.test");
        String admin = token("q-admin@example.test");
        String standard = token("q-std@example.test");

        assertThat(call(get(QUEUE), standard).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get(QUEUE + "?state=FAILED"), standard).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get(QUEUE), null).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get(QUEUE), "not-a-real-token").getResponse().getStatus()).isEqualTo(401);
        assertThat(call(get(QUEUE + "?state=BOGUS"), admin).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(get(QUEUE + "?size=101"), admin).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(get(QUEUE + "?page=-1"), admin).getResponse().getStatus()).isEqualTo(400);
        assertThat(call(post(QUEUE), admin).getResponse().getStatus()).isIn(403, 405);
    }

    // ---- utilidades ----

    private UUID enqueue(String recipient) {
        UUID id = UUID.randomUUID();
        var payload = cipher.encrypt(("{\"token\":\"" + SECRET_TOKEN + "\"}").getBytes(StandardCharsets.UTF_8));
        emails.insertPending(id, recipient, EmailTemplates.ACTIVATION, payload, clock.instant());
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM outbound_emails WHERE id = ?", id);
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((java.sql.Timestamp) row.get(column)).toInstant();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private void createUser(String email, String role) {
        OffsetDateTime at = OffsetDateTime.ofInstant(Instant.parse("2025-12-01T00:00:00Z"), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                                   created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, UUID.randomUUID(), email, bcrypt.encode(PASSWORD), role, at, at, at);
    }

    private String token(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn();
    }
}
