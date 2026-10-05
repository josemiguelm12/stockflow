package com.stockflow.identity;

import com.stockflow.notification.application.EncryptedPayload;
import com.stockflow.notification.application.OutboxDispatcher;
import com.stockflow.notification.application.OutboxPayloadCipher;
import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import com.stockflow.support.RecordingEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(OutputCaptureExtension.class)
class PasswordRecoveryIT extends AbstractPostgresIT {

    private static final String OLD = "Passw0rd-secret";
    private static final String NEW = "N3wPassw0rd-ok";
    private static final String EMAIL = "user@example.test";
    private static final String UNIFORM = "If the account is eligible, a password reset email will be sent.";
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
    private OutboxDispatcher worker;
    @Autowired
    private RecordingEmailSender smtp;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
        smtp.reset();
    }

    // ---- helpers ----

    private UUID createUser(String email, String status, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                """, id, email, bcrypt.encode(OLD), role, status);
        return id;
    }

    private MvcResult forgot(String email) throws Exception {
        clock.advance(Duration.ofSeconds(1)); // enqueued_at estrictamente creciente con el reloj congelado
        return mvc.perform(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn();
    }

    private MvcResult reset(String token, String newPassword) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}")).andReturn();
    }

    private MvcResult change(String bearer, String current, String newPassword) throws Exception {
        var request = post("/api/v1/auth/password/change").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + newPassword + "\"}");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private String loginToken(String email, String password) throws Exception {
        MvcResult result = login(email, password);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private int me(String token) throws Exception {
        return mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    /** Token crudo del correo PENDING más reciente de esa plantilla, descifrando el outbox. */
    private String pendingToken(String email, String template) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT payload_ciphertext, payload_iv, payload_key_id FROM outbound_emails
                WHERE recipient_email = ? AND template_key = ? AND state = 'PENDING'
                ORDER BY enqueued_at DESC, id LIMIT 1
                """, email, template);
        byte[] plaintext = cipher.decrypt(new EncryptedPayload((byte[]) row.get("payload_ciphertext"),
                (byte[]) row.get("payload_iv"), (String) row.get("payload_key_id")));
        Matcher m = PAYLOAD_TOKEN.matcher(new String(plaintext, StandardCharsets.UTF_8));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private List<String> allPendingTokens(String email, String template) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT payload_ciphertext, payload_iv, payload_key_id FROM outbound_emails
                WHERE recipient_email = ? AND template_key = ? AND state = 'PENDING' ORDER BY enqueued_at, id
                """, email, template);
        List<String> tokens = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Matcher m = PAYLOAD_TOKEN.matcher(new String(cipher.decrypt(new EncryptedPayload((byte[]) row.get("payload_ciphertext"),
                    (byte[]) row.get("payload_iv"), (String) row.get("payload_key_id"))), StandardCharsets.UTF_8));
            assertThat(m.find()).isTrue();
            tokens.add(m.group(1));
        }
        return tokens;
    }

    private String passwordHash(String email) {
        return jdbc.queryForObject("SELECT password_hash FROM users WHERE email_normalized = ?", String.class, email);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int usableResetTokens() {
        return count("""
                SELECT count(*) FROM one_time_tokens WHERE purpose = 'PASSWORD_RESET'
                AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > ?
                """, OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
    }

    private String usersSnapshot() {
        return jdbc.queryForList("SELECT * FROM users ORDER BY email_normalized").toString();
    }

    // ---- T03-01 ----

    @Test
    void forgotRespondsIdenticallyForEveryAccountStateAndOnlyAnActiveAccountGetsATokenAndAnEmail() throws Exception {
        createUser("active@example.test", "ACTIVE", "STANDARD");
        createUser("pending@example.test", "PENDING_ACTIVATION", "STANDARD");
        createUser("disabled@example.test", "DISABLED", "STANDARD");
        String usersBefore = usersSnapshot();

        List<MvcResult> results = new ArrayList<>();
        for (String email : new String[]{"active@example.test", "pending@example.test", "disabled@example.test",
                "nobody@example.test", "  ACTIVE@Example.TEST "}) {
            results.add(forgot(email));
        }

        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isEqualTo(202);
            assertThat(result.getResponse().getContentAsString()).isEqualTo("{\"message\":\"" + UNIFORM + "\"}");
            assertThat(result.getResponse().getContentType()).isEqualTo(results.get(0).getResponse().getContentType());
        }
        // Solo la cuenta activa generó token y correo (la solicitud con espacios/mayúsculas es la misma cuenta).
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE purpose = 'PASSWORD_RESET'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbound_emails")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE recipient_email = 'active@example.test' "
                + "AND template_key = 'PASSWORD_RESET'")).isEqualTo(2);
        assertThat(usersSnapshot()).isEqualTo(usersBefore);
    }

    @Test
    void forgotWithAMalformedEmailIsA400ThatNeverEchoesTheValue() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");

        for (String bad : new String[]{"not-an-email", "a@b", "two words@example.test", "@example.test", "user@"}) {
            MvcResult result = forgot(bad);
            assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("\"field\":\"email\"").doesNotContain(bad);
        }
        assertThat(count("SELECT count(*) FROM one_time_tokens")).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();
    }

    // ---- T03-02 ----

    @Test
    void theTokenIsRandomHashedOnlyAndValidForThirtyMinutes(CapturedOutput output) throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");

        MvcResult first = forgot(EMAIL);
        Instant requestedAt = clock.instant();
        String token = pendingToken(EMAIL, "PASSWORD_RESET");

        assertThat(token).hasSizeGreaterThanOrEqualTo(43).matches("[A-Za-z0-9_-]+");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM one_time_tokens");
        assertThat(row.get("purpose")).isEqualTo("PASSWORD_RESET");
        assertThat((String) row.get("token_hash")).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant()).isEqualTo(requestedAt.plus(Duration.ofMinutes(30)));
        assertThat(row.get("consumed_at")).isNull();
        assertThat(row.get("invalidated_at")).isNull();

        // El token crudo no está en ninguna tabla (ni en el outbox cifrado), ni en la respuesta ni en los logs.
        for (String table : new String[]{"one_time_tokens", "outbound_emails", "users", "auth_sessions"}) {
            assertThat(jdbc.queryForObject("SELECT coalesce(string_agg(t::text, ' '), '') FROM " + table + " t", String.class))
                    .as(table).doesNotContain(token);
        }
        byte[] ciphertext = jdbc.queryForObject("SELECT payload_ciphertext FROM outbound_emails", byte[].class);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(token);
        assertThat(first.getResponse().getContentAsString()).doesNotContain(token);
        assertThat(output.getAll()).doesNotContain(token);

        // Un segundo token es distinto (CSPRNG).
        forgot(EMAIL);
        assertThat(pendingToken(EMAIL, "PASSWORD_RESET")).isNotEqualTo(token);
    }

    @Test
    void theTokenWorksUntilTheExactExpirationInstant() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        forgot(EMAIL);
        String token = pendingToken(EMAIL, "PASSWORD_RESET");

        clock.advance(Duration.ofMinutes(30).minusSeconds(1));
        assertThat(reset(token, NEW).getResponse().getStatus()).isEqualTo(204);

        createUser("late@example.test", "ACTIVE", "STANDARD");
        forgot("late@example.test");
        String late = pendingToken("late@example.test", "PASSWORD_RESET");
        clock.advance(Duration.ofMinutes(30));
        assertThat(reset(late, NEW).getResponse().getStatus()).as("expires_at > now: ya no vale en el instante exacto").isEqualTo(400);
    }

    // ---- T03-03 ----

    @Test
    void aSecondRequestInvalidatesThePreviousTokenAndTheNewOneStaysUsable() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        forgot(EMAIL);
        String oldToken = pendingToken(EMAIL, "PASSWORD_RESET");
        forgot(EMAIL);
        String newToken = pendingToken(EMAIL, "PASSWORD_RESET");

        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(usableResetTokens()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE invalidated_at IS NOT NULL")).isEqualTo(1);
        assertThat(reset(oldToken, NEW).getResponse().getStatus()).isEqualTo(400);
        assertThat(passwordHash(EMAIL)).satisfies(h -> assertThat(bcrypt.matches(OLD, h)).isTrue());

        assertThat(reset(newToken, NEW).getResponse().getStatus()).isEqualTo(204);
    }

    // ---- T03-04 ----

    @Test
    void forgotLeavesAnEncryptedPendingEmailAndNeverCallsSmtp() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");

        forgot(EMAIL);

        assertThat(smtp.sent()).isEmpty();
        Map<String, Object> mail = jdbc.queryForMap("SELECT * FROM outbound_emails");
        assertThat(mail.get("state")).isEqualTo("PENDING");
        assertThat(mail.get("template_key")).isEqualTo("PASSWORD_RESET");
        assertThat(mail.get("recipient_email")).isEqualTo(EMAIL);
        assertThat((byte[]) mail.get("payload_ciphertext")).isNotEmpty();
        assertThat((byte[]) mail.get("payload_iv")).hasSize(12);
        assertThat(mail.get("payload_key_id")).isEqualTo("test-key-1");
        assertThat(mail.get("sent_at")).isNull();
    }

    @Test
    void anOfflineSmtpDoesNotChangeTheAcceptedResponseAndLeavesTheEmailPending() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        smtp.setFailing(true);

        MvcResult result = forgot(EMAIL);
        OutboxDispatcher.Result dispatched = worker.dispatchPending(10);

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentAsString()).contains(UNIFORM);
        assertThat(dispatched.sent()).isZero();
        assertThat(dispatched.failed()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE state = 'PENDING' AND payload_ciphertext IS NOT NULL"))
                .isEqualTo(1);
        assertThat(usableResetTokens()).isEqualTo(1);
    }

    @Test
    void theWorkerDeliversTheResetTemplatePurgesThePayloadAndNeverResendsAndActivationStillWorks() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        forgot(EMAIL);
        String token = pendingToken(EMAIL, "PASSWORD_RESET");

        OutboxDispatcher.Result first = worker.dispatchPending(10);

        assertThat(first.sent()).isEqualTo(1);
        assertThat(smtp.sent()).hasSize(1);
        RecordingEmailSender.Sent mail = smtp.sent().get(0);
        assertThat(mail.recipient()).isEqualTo(EMAIL);
        assertThat(mail.subject()).contains("Restablezca");
        assertThat(mail.body()).contains("http://localhost:8080/reset-password#token=" + token)
                .doesNotContain("?token").doesNotContain("/activate");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbound_emails");
        assertThat(row.get("state")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("payload_ciphertext")).isNull();
        assertThat(row.get("payload_iv")).isNull();
        assertThat(row.get("payload_key_id")).isNull();
        assertThat(worker.dispatchPending(10).sent()).isZero();
        assertThat(smtp.sent()).hasSize(1);

        // El token del correo funciona, y la plantilla de activación sigue intacta.
        assertThat(reset(token, NEW).getResponse().getStatus()).isEqualTo(204);
        assertThat(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"fresh@example.test\",\"password\":\"" + OLD + "\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(201);
        assertThat(worker.dispatchPending(10).sent()).isEqualTo(1);
        assertThat(smtp.sent().get(1).body()).contains("http://localhost:8080/activate#token=")
                .doesNotContain("reset-password");
        assertThat(smtp.sent().get(1).subject()).contains("Active su cuenta");
    }

    // ---- T03-05 ----

    @Test
    void aValidTokenChangesTheHashAndOnlyTheNewPasswordLogsIn() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        jdbc.update("UPDATE users SET password_reset_required = true");
        String hashBefore = passwordHash(EMAIL);
        forgot(EMAIL);
        String token = pendingToken(EMAIL, "PASSWORD_RESET");

        MvcResult result = reset(token, NEW);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(result.getResponse().getHeader("Authorization")).isNull();
        String hashAfter = passwordHash(EMAIL);
        assertThat(hashAfter).isNotEqualTo(hashBefore).startsWith("$2");
        assertThat(bcrypt.matches(NEW, hashAfter)).isTrue();
        assertThat(bcrypt.matches(OLD, hashAfter)).isFalse();
        assertThat(jdbc.queryForObject("SELECT password_reset_required FROM users", Boolean.class)).isFalse();
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE consumed_at IS NOT NULL")).isEqualTo(1);
        assertThat(login(EMAIL, OLD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(EMAIL, NEW).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anInvalidNewPasswordNeitherConsumesTheTokenNorChangesAnything() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String sessionToken = loginToken(EMAIL, OLD);
        forgot(EMAIL);
        String token = pendingToken(EMAIL, "PASSWORD_RESET");
        String hashBefore = passwordHash(EMAIL);

        for (String weak : new String[]{"short1", "onlyletters", "12345678", "a".repeat(70) + "12345"}) {
            MvcResult result = reset(token, weak);
            assertThat(result.getResponse().getStatus()).as(weak).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("\"field\":\"newPassword\"")
                    .doesNotContain(weak).doesNotContain(token);
        }

        assertThat(passwordHash(EMAIL)).isEqualTo(hashBefore);
        assertThat(usableResetTokens()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM one_time_tokens WHERE consumed_at IS NOT NULL")).isZero();
        assertThat(me(sessionToken)).isEqualTo(200);
        assertThat(reset(token, NEW).getResponse().getStatus()).isEqualTo(204);
    }

    // ---- T03-06 ----

    @Test
    void everyKindOfUnusableTokenGetsTheSame400AndChangesNothing() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        createUser("disabled@example.test", "ACTIVE", "STANDARD");
        String sessionToken = loginToken(EMAIL, OLD);

        forgot(EMAIL);
        String usedToken = pendingToken(EMAIL, "PASSWORD_RESET");
        assertThat(reset(usedToken, NEW).getResponse().getStatus()).isEqualTo(204);
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE email_normalized = ?", UUID.class, EMAIL);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", bcrypt.encode(OLD), userId);
        String sessionAgain = loginToken(EMAIL, OLD);

        forgot(EMAIL);
        String invalidated = pendingToken(EMAIL, "PASSWORD_RESET");
        forgot(EMAIL);
        String expired = pendingToken(EMAIL, "PASSWORD_RESET");
        clock.advance(Duration.ofMinutes(30));
        // token de activación (otro propósito) de un usuario pendiente creado por la API
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"pending@example.test\",\"password\":\"" + OLD + "\"}"));
        String activationToken = pendingToken("pending@example.test", "ACTIVATION");
        // token válido de un usuario que se deshabilita después de pedirlo
        forgot("disabled@example.test");
        String ofDisabled = pendingToken("disabled@example.test", "PASSWORD_RESET");
        jdbc.update("UPDATE users SET account_status = 'DISABLED' WHERE email_normalized = 'disabled@example.test'");

        String hashBefore = passwordHash(EMAIL);
        int activeSessions = count("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL");
        List<String> bodies = new ArrayList<>();
        for (String bad : new String[]{"unknown-token-value", usedToken, invalidated, expired, activationToken, ofDisabled}) {
            MvcResult result = reset(bad, NEW);
            assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).doesNotContain(bad).doesNotContain(NEW);
            bodies.add(result.getResponse().getContentAsString());
        }

        assertThat(bodies).hasSize(6);
        assertThat(bodies.stream().distinct()).as("el mismo cuerpo para todas las causas").hasSize(1);
        assertThat(passwordHash(EMAIL)).isEqualTo(hashBefore);
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NULL")).isEqualTo(activeSessions);
        assertThat(sessionToken).isNotBlank();
        assertThat(sessionAgain).isNotBlank();
    }

    // ---- T03-07 ----

    @Test
    void aResetRevokesEverySessionOfTheUserAndCreatesNoneAndLeavesOtherUsersAlone() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        createUser("other@example.test", "ACTIVE", "STANDARD");
        String first = loginToken(EMAIL, OLD);
        clock.advance(Duration.ofSeconds(1));
        String second = loginToken(EMAIL, OLD);
        String othersToken = loginToken("other@example.test", OLD);
        assertThat(me(first)).isEqualTo(200);
        assertThat(me(second)).isEqualTo(200);
        int sessionsBefore = count("SELECT count(*) FROM auth_sessions");
        forgot(EMAIL);

        MvcResult result = reset(pendingToken(EMAIL, "PASSWORD_RESET"), NEW);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(me(first)).isEqualTo(401);
        assertThat(me(second)).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM auth_sessions")).as("no se crea sesión automática").isEqualTo(sessionsBefore);
        assertThat(count("SELECT count(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id "
                + "WHERE u.email_normalized = ? AND s.revoked_at IS NULL", EMAIL)).isZero();
        assertThat(me(othersToken)).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id "
                + "WHERE u.email_normalized = 'other@example.test' AND s.revoked_at IS NOT NULL")).isZero();
        assertThat(bcrypt.matches(OLD, passwordHash("other@example.test"))).isTrue();
    }

    // ---- T03-08 ----

    @Test
    void changeRequiresAnAuthenticatedUser() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String hashBefore = passwordHash(EMAIL);

        assertThat(change(null, OLD, NEW).getResponse().getStatus()).isEqualTo(401);
        assertThat(change("garbage", OLD, NEW).getResponse().getStatus()).isEqualTo(401);

        assertThat(passwordHash(EMAIL)).isEqualTo(hashBefore);
    }

    @Test
    void aWrongCurrentPasswordIsAGeneric400AndMutatesNothing() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String token = loginToken(EMAIL, OLD);
        forgot(EMAIL);
        String resetToken = pendingToken(EMAIL, "PASSWORD_RESET");
        String hashBefore = passwordHash(EMAIL);
        String usersBefore = usersSnapshot();

        MvcResult first = change(token, "Wr0ng-password", NEW);
        MvcResult second = change(token, "An0ther-wrong", NEW);

        assertThat(first.getResponse().getStatus()).isEqualTo(400);
        assertThat(first.getResponse().getContentAsString()).isEqualTo(second.getResponse().getContentAsString())
                .doesNotContain("Wr0ng-password").doesNotContain(NEW);
        assertThat(passwordHash(EMAIL)).isEqualTo(hashBefore);
        assertThat(usersSnapshot()).isEqualTo(usersBefore);
        assertThat(me(token)).isEqualTo(200);
        assertThat(usableResetTokens()).isEqualTo(1);
        assertThat(reset(resetToken, "S3cond-Passw0rd").getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void aWeakNewPasswordIsRejectedWithoutChangingAnything() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String token = loginToken(EMAIL, OLD);
        String hashBefore = passwordHash(EMAIL);

        for (String weak : new String[]{"short1", "onlyletters", "12345678", "a".repeat(70) + "12345"}) {
            MvcResult result = change(token, OLD, weak);
            assertThat(result.getResponse().getStatus()).as(weak).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("\"field\":\"newPassword\"").doesNotContain(weak);
        }

        assertThat(passwordHash(EMAIL)).isEqualTo(hashBefore);
        assertThat(me(token)).isEqualTo(200);
    }

    @Test
    void aSuccessfulChangeUpdatesTheHashInvalidatesResetTokensAndRevokesEverySessionIncludingTheCurrentOne() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        createUser("other@example.test", "ACTIVE", "STANDARD");
        jdbc.update("UPDATE users SET password_reset_required = true WHERE email_normalized = ?", EMAIL);
        String current = loginToken(EMAIL, OLD);
        clock.advance(Duration.ofSeconds(1));
        String another = loginToken(EMAIL, OLD);
        String othersToken = loginToken("other@example.test", OLD);
        forgot(EMAIL);
        String pendingReset = pendingToken(EMAIL, "PASSWORD_RESET");
        String hashBefore = passwordHash(EMAIL);

        MvcResult result = change(current, OLD, NEW);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        String hashAfter = passwordHash(EMAIL);
        assertThat(hashAfter).isNotEqualTo(hashBefore);
        assertThat(bcrypt.matches(NEW, hashAfter)).isTrue();
        assertThat(jdbc.queryForObject("SELECT password_reset_required FROM users WHERE email_normalized = ?",
                Boolean.class, EMAIL)).isFalse();
        assertThat(me(current)).as("el Bearer que autorizó el cambio").isEqualTo(401);
        assertThat(me(another)).isEqualTo(401);
        assertThat(usableResetTokens()).isZero();
        assertThat(reset(pendingReset, "S3cond-Passw0rd").getResponse().getStatus()).isEqualTo(400);
        assertThat(login(EMAIL, OLD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(EMAIL, NEW).getResponse().getStatus()).isEqualTo(200);
        // Otro usuario no se ve afectado.
        assertThat(me(othersToken)).isEqualTo(200);
        assertThat(bcrypt.matches(OLD, passwordHash("other@example.test"))).isTrue();
    }

    @Test
    void changeNeverResetsOrWeakensTheLoginLockoutAndAppliesToAdminsToo() throws Exception {
        createUser("admin@example.test", "ACTIVE", "ADMIN");
        String token = loginToken("admin@example.test", OLD);
        jdbc.update("UPDATE users SET failed_login_attempts = 3 WHERE email_normalized = 'admin@example.test'");
        Object lockedBefore = jdbc.queryForObject("SELECT locked_until FROM users WHERE email_normalized = 'admin@example.test'",
                Object.class);

        assertThat(change(token, OLD, NEW).getResponse().getStatus()).isEqualTo(204);

        assertThat(count("SELECT failed_login_attempts FROM users WHERE email_normalized = 'admin@example.test'")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT locked_until FROM users WHERE email_normalized = 'admin@example.test'",
                Object.class)).isEqualTo(lockedBefore);
        assertThat(login("admin@example.test", NEW).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void changeCannotTargetAnotherUser() throws Exception {
        UUID victim = createUser("victim@example.test", "ACTIVE", "STANDARD");
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String token = loginToken(EMAIL, OLD);
        String victimHash = passwordHash("victim@example.test");

        MvcResult result = mvc.perform(post("/api/v1/auth/password/change").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + victim + "\",\"email\":\"victim@example.test\","
                        + "\"currentPassword\":\"" + OLD + "\",\"newPassword\":\"" + NEW + "\"}")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(passwordHash("victim@example.test")).isEqualTo(victimHash);
        assertThat(bcrypt.matches(NEW, passwordHash(EMAIL))).isTrue();
    }

    // ---- T03-09 / T03-11 (logs) ----

    @Test
    void theWholeFlowLeavesNoTokenPasswordOrBearerInTheLogs(CapturedOutput output) throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String session = loginToken(EMAIL, OLD);
        forgot(EMAIL);
        String token = pendingToken(EMAIL, "PASSWORD_RESET");
        worker.dispatchPending(10);
        reset("bad-token-value-123", NEW);
        assertThat(reset(token, NEW).getResponse().getStatus()).isEqualTo(204);
        String second = loginToken(EMAIL, NEW);
        change(second, "Wr0ng-password", "Th1rd-Passw0rd");
        assertThat(change(second, NEW, "Th1rd-Passw0rd").getResponse().getStatus()).isEqualTo(204);

        String logs = output.getAll();
        assertThat(logs).doesNotContain(token).doesNotContain("bad-token-value-123").doesNotContain(OLD).doesNotContain(NEW)
                .doesNotContain("Wr0ng-password").doesNotContain("Th1rd-Passw0rd").doesNotContain(session)
                .doesNotContain(second).doesNotContain("Bearer ").doesNotContain("reset-password#token");
    }
}
