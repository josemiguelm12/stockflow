package com.stockflow.identity;

import com.stockflow.identity.adapter.out.security.JwtAccessTokenService;
import com.stockflow.notification.application.EncryptedPayload;
import com.stockflow.notification.application.OutboxPayloadCipher;
import com.stockflow.shared.config.JwtProperties;
import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import jakarta.servlet.http.Cookie;
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
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(OutputCaptureExtension.class)
class SessionAuthenticationIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String EMAIL = "user@example.test";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
    private static final Pattern OUTBOX_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private OutboxPayloadCipher cipher;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
    }

    // ---- helpers ----

    private UUID createUser(String email, String status, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                """, id, email, bcrypt.encode(PASSWORD), role, status);
        return id;
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private String accessToken(MvcResult result) throws Exception {
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).as("login response has an accessToken").isTrue();
        return m.group(1);
    }

    private String loginToken(String email) throws Exception {
        MvcResult result = login(email, PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return accessToken(result);
    }

    private MvcResult me(String token) throws Exception {
        return mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)).andReturn();
    }

    private int logout(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jwtPart(String token, int index) throws Exception {
        return json.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[index]), Map.class);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int failedAttempts(String email) {
        return count("SELECT failed_login_attempts FROM users WHERE email_normalized = ?", email);
    }

    private Instant lockedUntil(String email) {
        OffsetDateTime value = jdbc.queryForObject(
                "SELECT locked_until FROM users WHERE email_normalized = ?", OffsetDateTime.class, email);
        return value == null ? null : value.toInstant();
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
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

    // ---- T02-01 ----

    @Test
    void loginIssuesAFifteenMinuteJwtWithMinimalClaimsAndAPersistedSession() throws Exception {
        UUID userId = createUser(EMAIL, "ACTIVE", "STANDARD");
        Instant now = clock.instant();

        MvcResult result = login(EMAIL, PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.keySet()).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn");
        assertThat(body.get("tokenType")).isEqualTo("Bearer");
        assertThat(((Number) body.get("expiresIn")).intValue()).isEqualTo(900);

        String token = (String) body.get("accessToken");
        Map<String, Object> claims = jwtPart(token, 1);
        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "jti", "iat", "exp");
        assertThat(claims.get("sub")).isEqualTo(userId.toString());
        assertThat(((Number) claims.get("iat")).longValue()).isEqualTo(now.getEpochSecond());
        assertThat(((Number) claims.get("exp")).longValue() - ((Number) claims.get("iat")).longValue()).isEqualTo(900);
        assertThat(jwtPart(token, 0).get("alg")).isEqualTo("HS256");

        Map<String, Object> session = jdbc.queryForMap("SELECT * FROM auth_sessions");
        assertThat(session.get("jti").toString()).isEqualTo(claims.get("jti"));
        assertThat(session.get("user_id")).isEqualTo(userId);
        assertThat(((java.sql.Timestamp) session.get("issued_at")).toInstant()).isEqualTo(now);
        assertThat(((java.sql.Timestamp) session.get("expires_at")).toInstant()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(session.get("revoked_at")).isNull();
        assertThat(count("SELECT count(*) FROM auth_sessions")).isEqualTo(1);

        // El JWT completo (ni sus partes) no se guarda en ninguna columna.
        String stored = jdbc.queryForObject("SELECT row_to_json(s)::text FROM auth_sessions s", String.class);
        for (String part : token.split("\\.")) {
            assertThat(stored).doesNotContain(part);
        }
    }

    @Test
    void emailIsNormalizedAtLogin() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");

        assertThat(login("  USER@Example.TEST ", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- T02-02 ----

    @Test
    void unknownEmailAndWrongPasswordGetTheExactSame401() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        Object usersBefore = jdbc.queryForList("SELECT * FROM users").toString();

        MvcResult unknown = login("nobody@example.test", PASSWORD);
        MvcResult wrong = login(EMAIL, "Wr0ng-password");

        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(wrong.getResponse().getContentAsString());
        assertThat(unknown.getResponse().getContentType()).isEqualTo(wrong.getResponse().getContentType());
        assertThat(count("SELECT count(*) FROM auth_sessions")).isZero();
        // Solo el intento contra una cuenta real cuenta; el desconocido no altera ningún usuario.
        assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM users").toString()).isNotEqualTo(usersBefore);
        assertThat(failedAttempts(EMAIL)).isEqualTo(1);
    }

    @Test
    void unknownEmailAttemptsNeverChangeAnyUser() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String before = jdbc.queryForList("SELECT * FROM users").toString();

        for (int i = 0; i < 12; i++) {
            assertThat(login("ghost" + i + "@example.test", PASSWORD).getResponse().getStatus()).isEqualTo(401);
        }

        assertThat(jdbc.queryForList("SELECT * FROM users").toString()).isEqualTo(before);
    }

    @Test
    void inactiveAccountsWithTheCorrectPasswordAreRejectedAsNotActiveWithoutASession() throws Exception {
        createUser("pending@example.test", "PENDING_ACTIVATION", "STANDARD");
        createUser("disabled@example.test", "DISABLED", "STANDARD");

        for (String email : new String[]{"pending@example.test", "disabled@example.test"}) {
            MvcResult result = login(email, PASSWORD);
            assertThat(result.getResponse().getStatus()).as(email).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("accessToken");
        }
        assertThat(count("SELECT count(*) FROM auth_sessions")).isZero();

        // Con contraseña incorrecta no se revela que la cuenta está pendiente: es el mismo 401 genérico.
        MvcResult wrongOnPending = login("pending@example.test", "Wr0ng-password");
        MvcResult unknown = login("nobody@example.test", "Wr0ng-password");
        assertThat(wrongOnPending.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongOnPending.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
    }

    @Test
    void aMalformedEmailIsAControlled400AndALoginForAValidUnknownEmailKeepsTheSame401() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String usersBefore = jdbc.queryForList("SELECT * FROM users").toString();

        for (String bad : new String[]{"not-an-email", "a@b", "two words@example.test", "@example.test", "user@"}) {
            MvcResult result = login(bad, PASSWORD);
            assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("\"field\":\"email\"").doesNotContain(bad).doesNotContain(PASSWORD);
        }
        assertThat(count("SELECT count(*) FROM auth_sessions")).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM users").toString()).isEqualTo(usersBefore);

        // El 400 no depende de si la cuenta existe; un email válido desconocido y una contraseña incorrecta siguen iguales.
        MvcResult unknown = login("nobody@example.test", PASSWORD);
        MvcResult wrong = login(EMAIL, "Wr0ng-password");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(wrong.getResponse().getContentAsString());
    }

    // ---- T02-03 ----

    @Test
    void invalidMissingForgedExpiredRevokedAndSessionlessTokensAreRejected() throws Exception {
        UUID userId = createUser(EMAIL, "ACTIVE", "STANDARD");
        UUID otherId = createUser("other@example.test", "ACTIVE", "STANDARD");
        String token = loginToken(EMAIL);
        UUID jti = UUID.fromString((String) jwtPart(token, 1).get("jti"));
        assertThat(me(token).getResponse().getStatus()).isEqualTo(200);

        // Ausente, malformado y fuera de la cabecera Bearer.
        assertThat(mvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
        for (String bad : new String[]{"", "garbage", "a.b.c", token + "x", token.substring(0, token.length() - 3)}) {
            assertThat(me(bad).getResponse().getStatus()).as(bad).isEqualTo(401);
        }
        assertThat(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Basic " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/auth/me").param("access_token", token))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/auth/me").cookie(new Cookie("access_token", token)))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        // Firma incorrecta: otra clave, o la firma alterada.
        var foreign = new JwtAccessTokenService(new JwtProperties(
                Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8))), clock);
        Instant now = clock.instant();
        assertThat(me(foreign.issue(userId, jti, now, now.plus(Duration.ofMinutes(15)))).getResponse().getStatus()).isEqualTo(401);
        String[] parts = token.split("\\.");
        // Se cambia el PRIMER carácter de la firma: aporta 6 bits completos. Cambiar el último (solo 4 bits útiles en
        // Base64URL de 32 bytes) podía dejar los bytes decodificados intactos y volver la prueba intermitente.
        String altered = parts[0] + "." + parts[1] + "." + (parts[2].charAt(0) == 'A' ? 'B' : 'A') + parts[2].substring(1);
        assertThat(me(altered).getResponse().getStatus()).isEqualTo(401);

        // Firma válida pero sin sesión (jti desconocido) o con sesión de otro usuario.
        var forger = new JwtAccessTokenService(new JwtProperties(JWT_SECRET), clock);
        assertThat(me(forger.issue(userId, UUID.randomUUID(), now, now.plus(Duration.ofMinutes(15)))).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(me(forger.issue(otherId, jti, now, now.plus(Duration.ofMinutes(15)))).getResponse().getStatus())
                .isEqualTo(401);
        // Firma válida con la sesión real pero JWT ya vencido.
        assertThat(me(forger.issue(userId, jti, now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(1)))).getResponse().getStatus())
                .isEqualTo(401);

        // El token real sigue funcionando hasta que ocurre algo en el servidor.
        assertThat(me(token).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void expiredRevokedMissingSessionAndDisabledUserAreRejected() throws Exception {
        UUID userId = createUser(EMAIL, "ACTIVE", "STANDARD");

        // Expirado: pasan los 15 minutos (el JWT y la fila de sesión vencen).
        String expiring = loginToken(EMAIL);
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(me(expiring).getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        assertThat(me(expiring).getResponse().getStatus()).isEqualTo(401);

        // Revocado.
        String revoked = loginToken(EMAIL);
        jdbc.update("UPDATE auth_sessions SET revoked_at = now() WHERE jti = ?::uuid", jwtPart(revoked, 1).get("jti"));
        assertThat(me(revoked).getResponse().getStatus()).isEqualTo(401);

        // Sin fila de sesión.
        String orphan = loginToken(EMAIL);
        jdbc.update("DELETE FROM auth_sessions WHERE jti = ?::uuid", jwtPart(orphan, 1).get("jti"));
        assertThat(me(orphan).getResponse().getStatus()).isEqualTo(401);

        // Usuario deshabilitado después del login: el estado actual se consulta en cada petición.
        String valid = loginToken(EMAIL);
        assertThat(me(valid).getResponse().getStatus()).isEqualTo(200);
        jdbc.update("UPDATE users SET account_status = 'DISABLED' WHERE id = ?", userId);
        assertThat(me(valid).getResponse().getStatus()).isEqualTo(401);
    }

    // ---- T02-04 ----

    @Test
    void meReturnsOnlyIdEmailAndTheCurrentRoleReadFromTheServer() throws Exception {
        UUID userId = createUser(EMAIL, "ACTIVE", "STANDARD");
        String token = loginToken(EMAIL);

        MvcResult first = me(token);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = json.readValue(first.getResponse().getContentAsString(), Map.class);
        assertThat(body).containsOnlyKeys("id", "email", "role");
        assertThat(body.get("id")).isEqualTo(userId.toString());
        assertThat(body.get("email")).isEqualTo(EMAIL);
        assertThat(body.get("role")).isEqualTo("STANDARD");

        // El rol no viaja en el JWT: un cambio en la base se refleja de inmediato con el mismo token.
        assertThat(jwtPart(token, 1)).doesNotContainKeys("role", "email");
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", userId);
        assertThat(me(token).getResponse().getContentAsString()).contains("\"role\":\"ADMIN\"");
    }

    // ---- T02-05 ----

    @Test
    void logoutRevokesTheSessionImmediatelyAndLeavesOtherSessionsAlone() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        String token = loginToken(EMAIL);
        String other = loginToken(EMAIL);
        assertThat(count("SELECT count(*) FROM auth_sessions")).isEqualTo(2);

        MvcResult result = mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE revoked_at IS NOT NULL")).isEqualTo(1);
        assertThat(me(token).getResponse().getStatus()).isEqualTo(401);
        assertThat(logout(token)).isEqualTo(401);
        assertThat(me(other).getResponse().getStatus()).isEqualTo(200);
    }

    // ---- T02-06 ----

    @Test
    void fiveFailuresLockTheAccountForFifteenMinutesAndASuccessAfterwardClearsIt() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        Instant lockStart = clock.instant();

        for (int i = 1; i <= 4; i++) {
            assertThat(login(EMAIL, "Wr0ng-password").getResponse().getStatus()).isEqualTo(401);
            assertThat(failedAttempts(EMAIL)).isEqualTo(i);
            assertThat(lockedUntil(EMAIL)).isNull();
        }
        assertThat(login(EMAIL, "Wr0ng-password").getResponse().getStatus()).isEqualTo(401);
        assertThat(failedAttempts(EMAIL)).isEqualTo(5);
        assertThat(lockedUntil(EMAIL)).isEqualTo(lockStart.plus(Duration.ofMinutes(15)));

        // Durante el bloqueo, incluso la contraseña correcta falla: mismo 401, sin sesión y sin tocar el contador.
        MvcResult duringLock = login(EMAIL, PASSWORD);
        MvcResult wrong = login(EMAIL, "Wr0ng-password");
        assertThat(duringLock.getResponse().getStatus()).isEqualTo(401);
        assertThat(duringLock.getResponse().getContentAsString()).isEqualTo(wrong.getResponse().getContentAsString());
        assertThat(count("SELECT count(*) FROM auth_sessions")).isZero();
        assertThat(failedAttempts(EMAIL)).isEqualTo(5);

        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM auth_sessions")).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(failedAttempts(EMAIL)).isZero();
        assertThat(lockedUntil(EMAIL)).isNull();
        assertThat(count("SELECT count(*) FROM auth_sessions")).isEqualTo(1);
    }

    @Test
    void aFailureAfterTheLockExpiresStartsAnotherFifteenMinuteLock() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        for (int i = 0; i < 5; i++) {
            login(EMAIL, "Wr0ng-password");
        }
        clock.advance(Duration.ofMinutes(15));

        assertThat(login(EMAIL, "Wr0ng-password").getResponse().getStatus()).isEqualTo(401);

        assertThat(failedAttempts(EMAIL)).isEqualTo(6);
        assertThat(lockedUntil(EMAIL)).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void aSuccessBeforeTheThresholdResetsTheCounter() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");
        for (int i = 0; i < 4; i++) {
            login(EMAIL, "Wr0ng-password");
        }
        assertThat(failedAttempts(EMAIL)).isEqualTo(4);

        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(failedAttempts(EMAIL)).isZero();
        for (int i = 0; i < 4; i++) {
            login(EMAIL, "Wr0ng-password");
        }
        assertThat(lockedUntil(EMAIL)).isNull();
    }

    // ---- T02-07 ----

    @Test
    void concurrentFailuresAreNeverLostAndTheThresholdLocksTheAccount() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");

        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            attempts.add(() -> login(EMAIL, "Wr0ng-password").getResponse().getStatus());
        }
        assertThat(runConcurrently(attempts)).containsOnly(401);

        assertThat(failedAttempts(EMAIL)).isEqualTo(5);
        assertThat(lockedUntil(EMAIL)).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void attemptsBeyondTheThresholdDoNotKeepCountingAndNoSessionIsCreatedDuringTheLock() throws Exception {
        createUser(EMAIL, "ACTIVE", "STANDARD");

        List<Callable<Integer>> wrong = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            wrong.add(() -> login(EMAIL, "Wr0ng-password").getResponse().getStatus());
        }
        assertThat(runConcurrently(wrong)).containsOnly(401);
        assertThat(failedAttempts(EMAIL)).isEqualTo(5);

        List<Callable<Integer>> correct = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            correct.add(() -> login(EMAIL, PASSWORD).getResponse().getStatus());
        }
        assertThat(runConcurrently(correct)).containsOnly(401);
        assertThat(count("SELECT count(*) FROM auth_sessions")).isZero();
        assertThat(failedAttempts(EMAIL)).isEqualTo(5);
    }

    // ---- T02-09 ----

    @Test
    void theWholeFlowWorksFromRegistrationAndNothingSensitiveReachesTheLogs(CapturedOutput output) throws Exception {
        assertThat(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(201);

        // Antes de activar, la cuenta rechaza el login con el mensaje de cuenta no activa.
        assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(403);

        Map<String, Object> mail = jdbc.queryForMap(
                "SELECT payload_ciphertext, payload_iv, payload_key_id FROM outbound_emails WHERE state = 'PENDING'");
        Matcher activation = OUTBOX_TOKEN.matcher(new String(cipher.decrypt(new EncryptedPayload(
                (byte[]) mail.get("payload_ciphertext"), (byte[]) mail.get("payload_iv"), (String) mail.get("payload_key_id"))),
                StandardCharsets.UTF_8));
        assertThat(activation.find()).isTrue();
        assertThat(mvc.perform(post("/api/v1/auth/activate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + activation.group(1) + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(204);

        String token = loginToken(EMAIL);
        assertThat(me(token).getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        // Autenticado o no, lo que no está autorizado sigue cerrado.
        assertThat(mvc.perform(get("/api/v1/admin/users")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(logout(token)).isEqualTo(204);
        login(EMAIL, "Wr0ng-password");

        String logs = output.getAll();
        assertThat(logs).doesNotContain(token).doesNotContain(PASSWORD).doesNotContain("Wr0ng-password")
                .doesNotContain(JWT_SECRET).doesNotContain(activation.group(1)).doesNotContain("Bearer ");
        for (String part : token.split("\\.")) {
            assertThat(logs).doesNotContain(part);
        }
    }
}
