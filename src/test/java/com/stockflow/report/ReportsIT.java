package com.stockflow.report;

import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** RF-REP-01…05 sobre PostgreSQL real, con datos conocidos, límites exactos y alcance por rol. */
class ReportsIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String REQUESTS = "/api/v1/reports/permission-requests";
    private static final String APPROVALS = "/api/v1/reports/purchase-order-approval-efficiency";
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private UUID adminId;
    private UUID std1Id;
    private UUID std2Id;
    private String admin;
    private String std1;
    private String std2;

    @BeforeEach
    void seed() throws Exception {
        adminId = createUser("rep-admin@example.test", "ADMIN");
        std1Id = createUser("rep-std1@example.test", "STANDARD");
        std2Id = createUser("rep-std2@example.test", "STANDARD");
        admin = token("rep-admin@example.test");
        std1 = token("rep-std1@example.test");
        std2 = token("rep-std2@example.test");

        // Solicitudes de permiso (requested_at).
        request(std1Id, "PENDING", "2026-03-01T00:00:00Z");   // límite inferior inclusivo
        request(std1Id, "REJECTED", "2026-03-01T12:00:00Z");
        request(std1Id, "APPLIED", "2026-03-02T08:00:00Z");
        request(std2Id, "PENDING", "2026-03-02T09:00:00Z");
        request(std2Id, "PENDING", "2026-03-10T00:00:00Z");   // límite superior excluido
        request(std1Id, "PENDING", "2026-02-28T23:59:59Z");   // fuera por 1 s

        // Órdenes (owner, submitted_at, approved_at).
        order(std1Id, "2026-03-01T10:00:00Z", "2026-03-01T10:10:00Z");   //   600 s
        order(std1Id, "2026-03-01T08:00:00Z", "2026-03-01T10:00:00Z");   //  7200 s
        order(std2Id, "2026-03-02T00:00:00Z", "2026-03-02T00:01:00Z");   //    60 s
        order(std1Id, "2026-03-09T00:00:00Z", "2026-03-10T00:00:00Z");   // 86400 s, aprobada justo en 'hasta'
        order(null, "2026-03-01T09:00:00Z", "2026-03-01T09:30:00Z");     //  1800 s, histórica sin propietario
        order(std1Id, null, "2026-03-01T11:00:00Z");                     // sin submitted_at: excluida
        order(std1Id, "2026-03-01T11:00:00Z", null);                     // aún sin aprobar: excluida
        order(null, null, null);                                         // histórica sin fechas: excluida
    }

    // ---- RF-REP-01 ----

    @Test
    void requestsAreCountedByStatusAndDayWithExactIntervalBounds() throws Exception {
        JsonNode body = ok(REQUESTS + "?desde=2026-03-01&hasta=2026-03-10", admin);

        assertThat(body.get("scope").asString()).isEqualTo("GLOBAL");
        assertThat(body.get("granularity").asString()).isEqualTo("DAY");
        assertThat(body.get("from").asString()).isEqualTo("2026-03-01T00:00:00Z");
        assertThat(body.get("to").asString()).isEqualTo("2026-03-10T00:00:00Z");
        assertThat(groups(body)).containsExactly(
                "2026-03-01T00:00:00Z|PENDING|1", "2026-03-01T00:00:00Z|REJECTED|1",
                "2026-03-02T00:00:00Z|APPLIED|1", "2026-03-02T00:00:00Z|PENDING|1");
    }

    @Test
    void twoDifferentRangesGiveDifferentAggregates() throws Exception {
        assertThat(groups(ok(REQUESTS + "?desde=2026-03-01&hasta=2026-03-02", admin)))
                .containsExactly("2026-03-01T00:00:00Z|PENDING|1", "2026-03-01T00:00:00Z|REJECTED|1");
        assertThat(groups(ok(REQUESTS + "?desde=2026-03-02&hasta=2026-03-11", admin)))
                .containsExactly("2026-03-02T00:00:00Z|APPLIED|1", "2026-03-02T00:00:00Z|PENDING|1",
                        "2026-03-10T00:00:00Z|PENDING|1");
    }

    @Test
    void monthlyGranularityAddsUpTheMonthAndWeeksStartOnMonday() throws Exception {
        assertThat(groups(ok(REQUESTS + "?desde=2026-03-01&hasta=2026-03-10&granularity=MONTH", admin)))
                .containsExactly("2026-03-01T00:00:00Z|APPLIED|1", "2026-03-01T00:00:00Z|PENDING|2",
                        "2026-03-01T00:00:00Z|REJECTED|1");
        // 2026-03-01 es domingo: pertenece a la semana ISO que empieza el lunes 2026-02-23.
        assertThat(groups(ok(REQUESTS + "?desde=2026-03-01&hasta=2026-03-02&granularity=week", admin)))
                .containsExactly("2026-02-23T00:00:00Z|PENDING|1", "2026-02-23T00:00:00Z|REJECTED|1");
    }

    @Test
    void aStandardUserSeesOnlyTheirOwnRequestsAndAForgedUserIdChangesNothing() throws Exception {
        String range = "?desde=2026-03-01&hasta=2026-03-10";

        JsonNode own = ok(REQUESTS + range, std1);
        assertThat(own.get("scope").asString()).isEqualTo("OWN");
        assertThat(groups(own)).containsExactly("2026-03-01T00:00:00Z|PENDING|1",
                "2026-03-01T00:00:00Z|REJECTED|1", "2026-03-02T00:00:00Z|APPLIED|1");

        assertThat(groups(ok(REQUESTS + range + "&userId=" + std2Id + "&requestedBy=" + adminId, std1)))
                .containsExactlyElementsOf(groups(own));
        assertThat(groups(ok(REQUESTS + range, std2))).containsExactly("2026-03-02T00:00:00Z|PENDING|1");
    }

    // ---- RF-REP-02 ----

    @Test
    void approvalEfficiencyIsCorrectForKnownDurationsAndExcludesIncompleteRows() throws Exception {
        JsonNode body = ok(APPROVALS + "?desde=2026-03-01&hasta=2026-03-10", admin);

        assertThat(body.get("unit").asString()).isEqualTo("SECONDS");
        assertThat(body.get("scope").asString()).isEqualTo("GLOBAL");
        // 1 mar: 600, 7200 y 1800 (la orden histórica con ambas fechas cuenta en el alcance global).
        assertThat(approvals(body)).containsExactly("2026-03-01T00:00:00Z|3|3200.0|600.0|7200.0",
                "2026-03-02T00:00:00Z|1|60.0|60.0|60.0");
    }

    @Test
    void theApprovalBoundariesAreInclusiveFromAndExclusiveTo() throws Exception {
        assertThat(approvals(ok(APPROVALS + "?desde=2026-03-10&hasta=2026-03-11", admin)))
                .containsExactly("2026-03-10T00:00:00Z|1|86400.0|86400.0|86400.0");
        assertThat(approvals(ok(APPROVALS + "?desde=2026-03-09T00:00:00Z&hasta=2026-03-10T00:00:00Z", admin)))
                .as("approved_at == hasta is excluded").isEmpty();
    }

    @Test
    void approvalDifferentRangesAndMonthlyBucket() throws Exception {
        assertThat(approvals(ok(APPROVALS + "?desde=2026-03-02&hasta=2026-03-03", admin)))
                .containsExactly("2026-03-02T00:00:00Z|1|60.0|60.0|60.0");
        // Mes completo: 3 + 1 + 1 = 5 órdenes con ambas fechas; media (600+7200+1800+60+86400)/5.
        assertThat(approvals(ok(APPROVALS + "?desde=2026-03-01&hasta=2026-04-01&granularity=MONTH", admin)))
                .containsExactly("2026-03-01T00:00:00Z|5|19212.0|60.0|86400.0");
    }

    @Test
    void standardUsersGetOnlyTheirOwnOrdersAndNeverTheOwnerlessHistoricalOnes() throws Exception {
        assertThat(approvals(ok(APPROVALS + "?desde=2026-03-01&hasta=2026-03-10", std1)))
                .containsExactly("2026-03-01T00:00:00Z|2|3900.0|600.0|7200.0");
        assertThat(approvals(ok(APPROVALS + "?desde=2026-03-01&hasta=2026-03-10&userId=" + std1Id, std2)))
                .containsExactly("2026-03-02T00:00:00Z|1|60.0|60.0|60.0");
        assertThat(ok(APPROVALS + "?desde=2026-03-01&hasta=2026-03-10", std1).get("scope").asString())
                .isEqualTo("OWN");
    }

    // ---- RF-REP-03/05: vacío, formato, rangos, predeterminado ----

    @Test
    void emptyResultsAreAnEmptyGroupListNotAnError() throws Exception {
        assertThat(ok(REQUESTS + "?desde=2020-01-01&hasta=2020-02-01", admin).get("groups")).isEmpty();
        assertThat(ok(APPROVALS + "?desde=2020-01-01&hasta=2020-02-01", std1).get("groups")).isEmpty();
        assertThat(ok(REQUESTS + "?desde=2026-03-01&hasta=2026-03-01", admin).get("groups")).isEmpty();
    }

    @Test
    void responsesContainOnlyAggregatesNoIdsOrOwners() throws Exception {
        String requests = call(get(REQUESTS + "?desde=2026-03-01&hasta=2026-03-10"), admin).getResponse()
                .getContentAsString();
        String orders = call(get(APPROVALS + "?desde=2026-03-01&hasta=2026-03-10"), admin).getResponse()
                .getContentAsString();

        for (String body : new String[]{requests, orders}) {
            assertThat(body).doesNotContain(std1Id.toString()).doesNotContain(std2Id.toString())
                    .doesNotContain(adminId.toString()).doesNotContain("\"id\"").doesNotContain("example.test")
                    .doesNotContain("owner").doesNotContain("requestedBy");
        }
    }

    @Test
    void invalidRangesAndParametersAreRejectedWith400() throws Exception {
        for (String path : new String[]{REQUESTS, APPROVALS}) {
            assertThat(status(path + "?desde=2026-03-02&hasta=2026-03-01", admin)).as("inverted").isEqualTo(400);
            assertThat(status(path + "?desde=ayer", admin)).as("format").isEqualTo(400);
            assertThat(status(path + "?hasta=2026-13-45", admin)).as("format").isEqualTo(400);
            assertThat(status(path + "?desde=2026-03-01T00:00:00&hasta=2026-03-02", admin)).as("no zone").isEqualTo(400);
            assertThat(status(path + "?desde=2025-01-01&hasta=2026-01-03", admin)).as("367 days").isEqualTo(400);
            assertThat(status(path + "?desde=2025-01-01&hasta=2026-01-02", admin)).as("366 days").isEqualTo(200);
            assertThat(status(path + "?granularity=YEAR", admin)).as("granularity").isEqualTo(400);
        }
    }

    @Test
    void withoutBoundsTheLast30DaysUntilTheClockAreUsed() throws Exception {
        Instant now = clock.instant();
        request(std1Id, "PENDING", now.minus(Duration.ofDays(30)).toString());          // límite inclusivo
        request(std1Id, "PENDING", now.minus(Duration.ofDays(30)).minusSeconds(1).toString()); // fuera

        JsonNode body = ok(REQUESTS, admin);

        assertThat(body.get("to").asString()).isEqualTo(now.toString());
        assertThat(body.get("from").asString()).isEqualTo(now.minus(Duration.ofDays(30)).toString());
        assertThat(groups(body)).hasSize(1);
        assertThat(groups(body).get(0)).endsWith("|PENDING|1");
    }

    // ---- RF-REP-04: autenticación ----

    @Test
    void anonymousAndInvalidSessionsGet401OnBothReports() throws Exception {
        for (String path : new String[]{REQUESTS, APPROVALS}) {
            assertThat(status(path, null)).isEqualTo(401);
            assertThat(status(path + "?desde=2026-03-01&hasta=2026-03-10", null)).isEqualTo(401);
            assertThat(status(path, "not-a-real-token")).isEqualTo(401);
        }
    }

    // ---- utilidades ----

    private java.util.List<String> groups(JsonNode body) {
        var out = new java.util.ArrayList<String>();
        body.get("groups").forEach(g -> out.add(g.get("periodStart").asString() + "|" + g.get("status").asString() + "|"
                + g.get("count").asLong()));
        return out;
    }

    private java.util.List<String> approvals(JsonNode body) {
        var out = new java.util.ArrayList<String>();
        body.get("groups").forEach(g -> out.add(g.get("periodStart").asString() + "|" + g.get("approvedCount").asLong()
                + "|" + g.get("averageSeconds").asDouble() + "|" + g.get("minSeconds").asDouble() + "|"
                + g.get("maxSeconds").asDouble()));
        return out;
    }

    private void request(UUID user, String status, String requestedAt) {
        Instant at = Instant.parse(requestedAt);
        OffsetDateTime requested = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        OffsetDateTime decided = requested.plusHours(1);
        switch (status) {
            case "PENDING" -> jdbc.update("""
                    INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at)
                    VALUES (?, ?, 'PURCHASE_ORDER_CREATE', 'PENDING', ?)
                    """, UUID.randomUUID(), user, requested);
            case "REJECTED" -> jdbc.update("""
                    INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at,
                        decided_by, decided_at, rejection_reason)
                    VALUES (?, ?, 'PURCHASE_ORDER_CREATE', 'REJECTED', ?, ?, ?, 'no')
                    """, UUID.randomUUID(), user, requested, adminId, decided);
            case "APPLIED" -> jdbc.update("""
                    INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at,
                        decided_by, decided_at, applied_by, applied_at)
                    VALUES (?, ?, 'PURCHASE_ORDER_CREATE', 'APPLIED', ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), user, requested, adminId, decided, adminId, decided);
            default -> throw new IllegalArgumentException(status);
        }
    }

    private void order(UUID owner, String submittedAt, String approvedAt) {
        jdbc.update("""
                INSERT INTO purchase_orders (id, supplier_id, state, created_at, owner_user_id, submitted_at,
                                             approved_at)
                VALUES (?, NULL, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), approvedAt == null ? "SUBMITTED" : "APPROVED",
                OffsetDateTime.ofInstant(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), owner,
                submittedAt == null ? null : OffsetDateTime.ofInstant(Instant.parse(submittedAt), ZoneOffset.UTC),
                approvedAt == null ? null : OffsetDateTime.ofInstant(Instant.parse(approvedAt), ZoneOffset.UTC));
    }

    private UUID createUser(String email, String role) {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.ofInstant(Instant.parse("2025-12-01T00:00:00Z"), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                                   created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, id, email, bcrypt.encode(PASSWORD), role, at, at, at);
        return id;
    }

    private String token(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private JsonNode ok(String path, String bearer) throws Exception {
        MvcResult result = call(get(path), bearer);
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private int status(String path, String bearer) throws Exception {
        return call(get(path), bearer).getResponse().getStatus();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn();
    }
}
