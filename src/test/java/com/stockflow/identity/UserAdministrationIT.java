package com.stockflow.identity;

import com.stockflow.identity.application.BootstrapAdmin;
import com.stockflow.identity.application.UserAdministrationRepository;
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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(OutputCaptureExtension.class)
class UserAdministrationIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String NEW = "N3wPassw0rd-ok";
    private static final String USERS = "/api/v1/admin/users";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
    private static final Pattern PAYLOAD_TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

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
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private UserAdministrationRepository admins;
    @Autowired
    private ApplicationContext context;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
        smtp.reset();
    }

    // ---- helpers ----

    private UUID createUser(String email, String role, String status) {
        return createUser(email, role, status, !"PENDING_ACTIVATION".equals(status), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private UUID createUser(String email, String role, String status, boolean everActivated, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                                   created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, email, bcrypt.encode(PASSWORD), role, status,
                everActivated ? OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC) : null,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return id;
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn();
    }

    private String token(String email) throws Exception {
        MvcResult result = login(email, PASSWORD);
        assertThat(result.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private int call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        return send(request, bearer).getResponse().getStatus();
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn();
    }

    private MockHttpServletRequestBuilder role(UUID id, String role) {
        return patch(USERS + "/" + id + "/role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}");
    }

    private MockHttpServletRequestBuilder status(UUID id, String status) {
        return patch(USERS + "/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountStatus\":\"" + status + "\"}");
    }

    private MockHttpServletRequestBuilder forceReset(UUID id) {
        return post(USERS + "/" + id + "/force-password-reset");
    }

    private int me(String bearer) throws Exception {
        return call(get("/api/v1/auth/me"), bearer);
    }

    private String column(String column, UUID id) {
        return String.valueOf(jdbc.queryForObject("SELECT " + column + " FROM users WHERE id = ?", Object.class, id));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int activeAdmins() {
        return count("SELECT count(*) FROM users WHERE role = 'ADMIN' AND account_status = 'ACTIVE'");
    }

    private String snapshot() {
        return jdbc.queryForList("SELECT * FROM users ORDER BY id").toString()
                + jdbc.queryForList("SELECT * FROM auth_sessions ORDER BY jti")
                + jdbc.queryForList("SELECT * FROM one_time_tokens ORDER BY id")
                + jdbc.queryForList("SELECT id, state FROM outbound_emails ORDER BY id");
    }

    private List<String> resetTokens(String email) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT payload_ciphertext, payload_iv, payload_key_id FROM outbound_emails
                WHERE recipient_email = ? AND template_key = 'PASSWORD_RESET' AND state = 'PENDING' ORDER BY enqueued_at, id
                """, email);
        List<String> tokens = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Matcher m = PAYLOAD_TOKEN.matcher(new String(cipher.decrypt(new EncryptedPayload((byte[]) row.get("payload_ciphertext"),
                    (byte[]) row.get("payload_iv"), (String) row.get("payload_key_id"))), StandardCharsets.UTF_8));
            assertThat(m.find()).isTrue();
            tokens.add(m.group(1));
        }
        return tokens;
    }

    private int reset(String token, String newPassword) throws Exception {
        return call(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"), null);
    }

    private void awaitLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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
        throw new AssertionError("Expected " + expected + " transaction(s) waiting on the admin membership lock");
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

    // ---- T04-01 ----

    @Test
    void withoutABearerEveryAdminEndpointIs401AndAStandardBearerIs403WithoutChangingAnything() throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        createUser("user@example.test", "STANDARD", "ACTIVE");
        UUID target = createUser("target@example.test", "STANDARD", "ACTIVE");
        String standard = token("user@example.test");
        String before = snapshot();

        for (var request : List.of(get(USERS), role(target, "ADMIN"), status(target, "DISABLED"), forceReset(target))) {
            assertThat(call(request, null)).isEqualTo(401);
        }
        for (var request : List.of(get(USERS), role(target, "ADMIN"), status(target, "DISABLED"), forceReset(target))) {
            MvcResult result = send(request, standard);
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("target@example.test");
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(context.getBeanNamesForType(BootstrapAdmin.class)).as("sin bootstrap en la aplicación web").isEmpty();
    }

    // ---- T04-02 ----

    @Test
    void theListIsPagedInAStableOrderWithOnlyTheAllowedFields() throws Exception {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        createUser("admin@example.test", "ADMIN", "ACTIVE", true, t0);
        UUID tieA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID tieB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        for (UUID id : new UUID[]{tieB, tieA}) {
            jdbc.update("""
                    INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
                    VALUES (?, ?, 'x', 'STANDARD', 'PENDING_ACTIVATION', ?, ?)
                    """, id, "tie-" + id.toString().substring(35) + "@example.test",
                    OffsetDateTime.ofInstant(t0.plusSeconds(10), ZoneOffset.UTC), OffsetDateTime.ofInstant(t0.plusSeconds(10), ZoneOffset.UTC));
        }
        createUser("late@example.test", "STANDARD", "DISABLED", true, t0.plusSeconds(20));
        jdbc.update("UPDATE users SET failed_login_attempts = 4, locked_until = now() WHERE email_normalized = ?",
                "late@example.test");
        String admin = token("admin@example.test");

        MvcResult first = send(get(USERS).param("page", "0").param("size", "2"), admin);
        MvcResult second = send(get(USERS).param("page", "1").param("size", "2"), admin);
        MvcResult beyond = send(get(USERS).param("page", "9").param("size", "2"), admin);

        @SuppressWarnings("unchecked")
        Map<String, Object> page0 = json.readValue(first.getResponse().getContentAsString(), Map.class);
        assertThat(page0).containsOnlyKeys("items", "page", "size", "totalElements", "totalPages");
        assertThat(page0.get("totalElements")).isEqualTo(4);
        assertThat(page0.get("totalPages")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page0.get("items");
        assertThat(items).extracting(i -> i.get("email")).containsExactly("admin@example.test", "tie-a@example.test");
        assertThat(items.get(0)).containsOnlyKeys("id", "email", "role", "accountStatus", "passwordResetRequired",
                "createdAt", "updatedAt");
        assertThat(items.get(0).get("createdAt")).isEqualTo("2026-01-01T00:00:00Z");
        @SuppressWarnings("unchecked")
        Map<String, Object> page1 = json.readValue(second.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items1 = (List<Map<String, Object>>) page1.get("items");
        assertThat(items1).extracting(i -> i.get("email")).containsExactly("tie-b@example.test", "late@example.test");
        assertThat(beyond.getResponse().getContentAsString()).contains("\"items\":[]");

        String all = first.getResponse().getContentAsString() + second.getResponse().getContentAsString();
        assertThat(all).doesNotContainIgnoringCase("hash").doesNotContainIgnoringCase("failed")
                .doesNotContainIgnoringCase("locked").doesNotContainIgnoringCase("token").doesNotContain("$2");

        for (String[] bad : new String[][]{{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"page", "abc"}, {"size", "x"}}) {
            assertThat(call(get(USERS).param(bad[0], bad[1]), admin)).as(bad[0] + "=" + bad[1]).isEqualTo(400);
        }
        assertThat(call(get(USERS).param("size", "100"), admin)).isEqualTo(200);
    }

    // ---- T04-03 ----

    @Test
    void anAdminChangesAnotherUsersRoleAndTheChangeAppliesToTheirNextRequest() throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID target = createUser("user@example.test", "STANDARD", "ACTIVE");
        String admin = token("admin@example.test");
        String targetBearer = token("user@example.test");
        assertThat(call(get(USERS), targetBearer)).isEqualTo(403);

        assertThat(call(role(target, "ADMIN"), admin)).isEqualTo(204);
        assertThat(column("role", target)).isEqualTo("ADMIN");
        assertThat(call(get(USERS), targetBearer)).as("mismo Bearer, rol nuevo").isEqualTo(200);

        String updatedAt = column("updated_at", target);
        clock.advance(Duration.ofMinutes(1));
        assertThat(call(role(target, "ADMIN"), admin)).as("idempotente").isEqualTo(204);
        assertThat(column("updated_at", target)).isEqualTo(updatedAt);

        for (String bad : new String[]{"SUPER", "admin", "ROLE_ADMIN", ""}) {
            assertThat(call(role(target, bad), admin)).as(bad).isEqualTo(400);
        }
        assertThat(call(role(target, "STANDARD"), admin)).isEqualTo(204);
        assertThat(call(get(USERS), targetBearer)).isEqualTo(403);
        assertThat(call(role(UUID.randomUUID(), "ADMIN"), admin)).isEqualTo(404);
        assertThat(call(patch(USERS + "/not-a-uuid/role").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"),
                admin)).isEqualTo(400);
        assertThat(call(patch(USERS + "/" + target + "/role").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\",\"userId\":\"" + target + "\"}"), admin)).isEqualTo(400);
        assertThat(column("role", target)).isEqualTo("STANDARD");
    }

    // ---- T04-04 ----

    @Test
    void anAdminCannotChangeTheirOwnRole() throws Exception {
        UUID adminId = createUser("admin@example.test", "ADMIN", "ACTIVE");
        createUser("second@example.test", "ADMIN", "ACTIVE");
        String admin = token("admin@example.test");

        MvcResult result = send(role(adminId, "STANDARD"), admin);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("\"title\":\"Conflict\"").doesNotContain("self");
        assertThat(call(role(adminId, "ADMIN"), admin)).isEqualTo(409);
        assertThat(column("role", adminId)).isEqualTo("ADMIN");
    }

    @Test
    void twoAdminsDemotingEachOtherAtTheSameTimeNeverLeaveZeroActiveAdmins() throws Exception {
        UUID a = createUser("a@example.test", "ADMIN", "ACTIVE");
        UUID b = createUser("b@example.test", "ADMIN", "ACTIVE");
        String tokenA = token("a@example.test");
        String tokenB = token("b@example.test");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new TransactionTemplate(transactionManager).execute(tx -> {
                admins.acquireAdminMembershipLock(); // ambas peticiones deben esperar este mismo bloqueo
                Future<Integer> aDemotesB = pool.submit(() -> call(role(b, "STANDARD"), tokenA));
                Future<Integer> bDemotesA = pool.submit(() -> call(role(a, "STANDARD"), tokenB));
                awaitLockWaiters(2);
                return List.of(aDemotesB, bDemotesA);
            });
            for (Future<Integer> f : futures) {
                statuses.add(f.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(204, 403);
        assertThat(activeAdmins()).isEqualTo(1);
    }

    @Test
    void racingDemotionsAndDeactivationsAmongAdminsAlwaysKeepAnActiveAdmin() throws Exception {
        for (int round = 0; round < 4; round++) {
            cleanDatabase();
            UUID a = createUser("a" + round + "@example.test", "ADMIN", "ACTIVE");
            UUID b = createUser("b" + round + "@example.test", "ADMIN", "ACTIVE");
            UUID c = createUser("c" + round + "@example.test", "ADMIN", "ACTIVE");
            String ta = token("a" + round + "@example.test");
            String tb = token("b" + round + "@example.test");
            String tc = token("c" + round + "@example.test");

            List<Integer> statuses = runConcurrently(List.of(
                    () -> call(role(b, "STANDARD"), ta),
                    () -> call(status(c, "DISABLED"), tb),
                    () -> call(role(a, "STANDARD"), tc),
                    () -> call(status(a, "DISABLED"), tb),
                    () -> call(role(c, "STANDARD"), ta)));

            assertThat(statuses).as("round " + round).allMatch(s -> s == 204 || s == 403 || s == 409 || s == 401);
            assertThat(activeAdmins()).as("round " + round).isGreaterThanOrEqualTo(1);
        }
    }

    // ---- T04-05 ----

    @Test
    void disablingAUserRevokesTheirSessionsAndBlocksLogin() throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID target = createUser("user@example.test", "STANDARD", "ACTIVE");
        String admin = token("admin@example.test");
        String first = token("user@example.test");
        String second = token("user@example.test");
        String updatedBefore = column("updated_at", target);
        clock.advance(Duration.ofMinutes(1));

        assertThat(call(status(target, "DISABLED"), admin)).isEqualTo(204);

        assertThat(column("account_status", target)).isEqualTo("DISABLED");
        assertThat(column("updated_at", target)).isNotEqualTo(updatedBefore);
        assertThat(me(first)).isEqualTo(401);
        assertThat(me(second)).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", target)).isZero();
        assertThat(login("user@example.test", PASSWORD).getResponse().getStatus()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", target)).isZero();
        assertThat(call(status(target, "DISABLED"), admin)).as("idempotente").isEqualTo(204);
        assertThat(me(admin)).as("el ADMIN conserva su sesión").isEqualTo(200);
    }

    @Test
    void anAdminCannotDisableThemselvesAndTwoAdminsDisablingEachOtherKeepOneActive() throws Exception {
        UUID a = createUser("a@example.test", "ADMIN", "ACTIVE");
        UUID b = createUser("b@example.test", "ADMIN", "ACTIVE");
        String tokenA = token("a@example.test");
        String tokenB = token("b@example.test");

        assertThat(call(status(a, "DISABLED"), tokenA)).isEqualTo(409);
        assertThat(column("account_status", a)).isEqualTo("ACTIVE");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new TransactionTemplate(transactionManager).execute(tx -> {
                admins.acquireAdminMembershipLock();
                Future<Integer> aDisablesB = pool.submit(() -> call(status(b, "DISABLED"), tokenA));
                Future<Integer> bDisablesA = pool.submit(() -> call(status(a, "DISABLED"), tokenB));
                awaitLockWaiters(2);
                return List.of(aDisablesB, bDisablesA);
            });
            for (Future<Integer> f : futures) {
                statuses.add(f.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(204, 403);
        assertThat(activeAdmins()).isEqualTo(1);
    }

    // ---- T04-06 ----

    @Test
    void reactivationRestoresAccessWithoutRevivingSessionsOrTouchingTheLockout() throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID target = createUser("user@example.test", "STANDARD", "ACTIVE");
        String admin = token("admin@example.test");
        String old = token("user@example.test");
        assertThat(call(status(target, "DISABLED"), admin)).isEqualTo(204);
        jdbc.update("UPDATE users SET failed_login_attempts = 3, locked_until = ? WHERE id = ?",
                OffsetDateTime.ofInstant(clock.instant().plus(Duration.ofMinutes(5)), ZoneOffset.UTC), target);
        String lockout = column("failed_login_attempts", target) + "|" + column("locked_until", target);

        assertThat(call(status(target, "ACTIVE"), admin)).isEqualTo(204);

        assertThat(column("account_status", target)).isEqualTo("ACTIVE");
        assertThat(me(old)).as("las sesiones revocadas no reviven").isEqualTo(401);
        assertThat(column("failed_login_attempts", target) + "|" + column("locked_until", target)).isEqualTo(lockout);
        assertThat(call(status(target, "ACTIVE"), admin)).as("idempotente").isEqualTo(204);
        clock.advance(Duration.ofMinutes(5));
        assertThat(login("user@example.test", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void invalidStatusTransitionsAreConflictsAndUnknownValuesAreBadRequests() throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID pending = createUser("pending@example.test", "STANDARD", "PENDING_ACTIVATION");
        UUID neverActivated = createUser("never@example.test", "STANDARD", "DISABLED", false,
                Instant.parse("2026-01-01T00:00:00Z"));
        String admin = token("admin@example.test");
        String before = snapshot();

        assertThat(call(status(pending, "ACTIVE"), admin)).isEqualTo(409);
        assertThat(call(status(pending, "DISABLED"), admin)).isEqualTo(409);
        assertThat(call(status(neverActivated, "ACTIVE"), admin)).isEqualTo(409);
        for (String bad : new String[]{"PENDING_ACTIVATION", "active", "LOCKED", ""}) {
            assertThat(call(status(pending, bad), admin)).as(bad).isEqualTo(400);
        }
        assertThat(call(status(UUID.randomUUID(), "DISABLED"), admin)).isEqualTo(404);
        assertThat(snapshot()).isEqualTo(before);
    }

    // ---- T04-07 ----

    @Test
    void forcePasswordResetFlagsTheAccountRevokesSessionsAndQueuesAnEncryptedEmail(CapturedOutput output) throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID target = createUser("user@example.test", "STANDARD", "ACTIVE");
        String admin = token("admin@example.test");
        String targetBearer = token("user@example.test");
        call(post("/api/v1/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@example.test\"}"), null);
        String earlierToken = resetTokens("user@example.test").get(0);
        jdbc.update("UPDATE users SET failed_login_attempts = 2 WHERE id = ?", target);
        String hashBefore = column("password_hash", target);
        clock.advance(Duration.ofSeconds(1));

        MvcResult result = send(forceReset(target), admin);

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(column("password_reset_required", target)).isEqualTo("true");
        assertThat(column("password_hash", target)).as("no cambia el hash").isEqualTo(hashBefore);
        assertThat(me(targetBearer)).isEqualTo(401);
        assertThat(smtp.sent()).as("el caso de uso no llama a SMTP").isEmpty();

        MvcResult oldPasswordLogin = login("user@example.test", PASSWORD);
        assertThat(oldPasswordLogin.getResponse().getStatus()).isEqualTo(403);
        assertThat(oldPasswordLogin.getResponse().getContentAsString()).contains("Password reset required")
                .doesNotContain("accessToken");
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", target)).isZero();
        assertThat(column("failed_login_attempts", target)).as("el rechazo no limpia fallos").isEqualTo("2");
        MvcResult wrong = login("user@example.test", "Wr0ng-password");
        MvcResult unknown = login("nobody@example.test", "Wr0ng-password");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());

        assertThat(reset(earlierToken, NEW)).as("token anterior invalidado").isEqualTo(400);
        Map<String, Object> token = jdbc.queryForMap("""
                SELECT token_hash FROM one_time_tokens WHERE user_id = ? AND purpose = 'PASSWORD_RESET'
                AND consumed_at IS NULL AND invalidated_at IS NULL
                """, target);
        String newToken = resetTokens("user@example.test").get(1);
        assertThat((String) token.get("token_hash")).matches("[0-9a-f]{64}").isNotEqualTo(newToken);
        Map<String, Object> mail = jdbc.queryForList(
                "SELECT * FROM outbound_emails WHERE recipient_email = ? ORDER BY enqueued_at DESC", "user@example.test").get(0);
        assertThat(mail.get("state")).isEqualTo("PENDING");
        assertThat(mail.get("template_key")).isEqualTo("PASSWORD_RESET");
        assertThat(new String((byte[]) mail.get("payload_ciphertext"), StandardCharsets.ISO_8859_1)).doesNotContain(newToken);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(newToken);
        assertThat(output.getAll()).doesNotContain(newToken).doesNotContain("user@example.test")
                .contains("admin_event action=force_password_reset");
    }

    // ---- T04-08 ----

    @Test
    void aSecondForceResetInvalidatesTheFirstAndOnlyTheLastTokenCompletesTheReset() throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID target = createUser("user@example.test", "STANDARD", "ACTIVE");
        String admin = token("admin@example.test");

        assertThat(call(forceReset(target), admin)).isEqualTo(202);
        clock.advance(Duration.ofSeconds(1));
        assertThat(call(forceReset(target), admin)).isEqualTo(202);
        List<String> tokens = resetTokens("user@example.test");
        assertThat(tokens).hasSize(2);

        assertThat(reset(tokens.get(0), NEW)).isEqualTo(400);
        assertThat(column("password_reset_required", target)).isEqualTo("true");
        assertThat(reset(tokens.get(1), NEW)).isEqualTo(204);
        assertThat(column("password_reset_required", target)).isEqualTo("false");
        assertThat(login("user@example.test", PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login("user@example.test", NEW).getResponse().getStatus()).isEqualTo(200);
        assertThat(bcrypt.matches(NEW, column("password_hash", target))).isTrue();
    }

    @Test
    void forceResetRequiresAnExistingActiveTargetAndRevokesTheAdminsOwnSessionWhenSelfApplied() throws Exception {
        UUID adminId = createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID pending = createUser("pending@example.test", "STANDARD", "PENDING_ACTIVATION");
        UUID disabled = createUser("disabled@example.test", "STANDARD", "DISABLED");
        String admin = token("admin@example.test");
        String before = snapshot();

        assertThat(call(forceReset(UUID.randomUUID()), admin)).isEqualTo(404);
        assertThat(call(forceReset(pending), admin)).isEqualTo(409);
        assertThat(call(forceReset(disabled), admin)).isEqualTo(409);
        assertThat(snapshot()).isEqualTo(before);

        assertThat(call(forceReset(adminId), admin)).isEqualTo(202);
        assertThat(me(admin)).as("revoca también la sesión que autorizó la petición").isEqualTo(401);
        assertThat(column("password_reset_required", adminId)).isEqualTo("true");
    }

    @Test
    void forcePasswordResetWithAnyBodyIsRejectedWithoutTouchingTheUserTokensSessionsOrOutbox() throws Exception {
        UUID adminId = createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID target = createUser("user@example.test", "STANDARD", "ACTIVE");
        String admin = token("admin@example.test");
        String targetBearer = token("user@example.test");
        String before = snapshot();

        for (String body : new String[]{
                "{\"userId\":\"" + adminId + "\"}",
                "{\"email\":\"admin@example.test\"}",
                "{\"userId\":\"" + target + "\",\"email\":\"user@example.test\",\"passwordResetRequired\":false}",
                "{}", "null", "[]", "x"}) {
            MvcResult result = send(post(USERS + "/" + target + "/force-password-reset")
                    .contentType(MediaType.APPLICATION_JSON).content(body), admin);
            assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("user@example.test");
        }

        assertThat(snapshot()).isEqualTo(before);
        assertThat(column("password_reset_required", target)).isEqualTo("false");
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();
        assertThat(me(targetBearer)).isEqualTo(200);
        assertThat(call(forceReset(target), admin)).as("sin cuerpo sigue funcionando").isEqualTo(202);
    }

    // ---- T04-09 ----

    @Test
    void anOfflineSmtpKeepsThe202AndTheWorkerLaterDeliversTheResetEmailOnce() throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID target = createUser("user@example.test", "STANDARD", "ACTIVE");
        String admin = token("admin@example.test");
        smtp.setFailing(true);

        assertThat(call(forceReset(target), admin)).isEqualTo(202);
        OutboxDispatcher.Result offline = worker.dispatchPending(10);

        assertThat(offline.sent()).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE state = 'PENDING' AND template_key = 'PASSWORD_RESET'"))
                .isEqualTo(1);
        String token = resetTokens("user@example.test").get(0);

        smtp.setFailing(false);
        assertThat(worker.dispatchPending(10).sent()).isEqualTo(1);
        assertThat(smtp.sent().get(0).recipient()).isEqualTo("user@example.test");
        assertThat(smtp.sent().get(0).body()).contains("http://localhost:8080/reset-password#token=" + token);
        Map<String, Object> mail = jdbc.queryForMap("SELECT * FROM outbound_emails");
        assertThat(mail.get("state")).isEqualTo("SENT");
        assertThat(mail.get("payload_ciphertext")).isNull();
        assertThat(worker.dispatchPending(10).sent()).isZero();
        assertThat(smtp.sent()).hasSize(1);
        assertThat(reset(token, NEW)).isEqualTo(204);
    }

    @Test
    void administrativeEventsAreLoggedWithIdsOnly(CapturedOutput output) throws Exception {
        createUser("admin@example.test", "ADMIN", "ACTIVE");
        UUID target = createUser("user@example.test", "STANDARD", "ACTIVE");
        String admin = token("admin@example.test");

        call(role(target, "ADMIN"), admin);
        call(role(target, "STANDARD"), admin);
        call(status(target, "DISABLED"), admin);
        call(status(target, "ACTIVE"), admin);

        String logs = output.getAll();
        assertThat(logs).contains("admin_event action=change_role").contains("target=" + target)
                .contains("result=changed_to_ADMIN").contains("result=disabled").contains("result=reactivated");
        assertThat(logs).doesNotContain("user@example.test").doesNotContain("admin@example.test")
                .doesNotContain(PASSWORD).doesNotContain(admin).doesNotContain("Bearer ");
    }
}
