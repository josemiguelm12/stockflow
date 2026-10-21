package com.stockflow.permission;

import com.stockflow.support.AbstractPostgresIT;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A2-T04 de punta a punta: login real, Bearer real, SecurityConfig, controladores, casos de uso y PostgreSQL. No se
 * inyecta ninguna Authentication: el actor sale siempre de la sesión persistida.
 */
class PermissionHttpIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String ADMIN_EMAIL = "perm-http-admin@example.test";
    private static final String OTHER_ADMIN_EMAIL = "perm-http-admin-2@example.test";
    private static final String STANDARD_EMAIL = "perm-http-standard@example.test";
    private static final String OTHER_STANDARD_EMAIL = "perm-http-standard-2@example.test";
    private static final List<String> EMAILS = List.of(ADMIN_EMAIL, OTHER_ADMIN_EMAIL, STANDARD_EMAIL,
            OTHER_STANDARD_EMAIL);
    private static final String BASE = "/api/v1/permission-requests";
    private static final String ORDERS = "/api/v1/purchase-orders";
    private static final String CREATE_BODY = "{\"permissionCode\":\"PURCHASE_ORDER_CREATE\"}";
    private static final String REASON = "  Not needed for the current role  ";
    private static final Pattern ID = Pattern.compile("\"id\":\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final List<UUID> createdOrders = new ArrayList<>();

    private UUID adminId;
    private UUID otherAdminId;
    private UUID standardId;
    private UUID otherStandardId;
    private String admin;
    private String otherAdmin;
    private String standard;
    private String otherStandard;

    /** Otras IT borran {@code users}; las FK RESTRICT exigen limpiar permisos, órdenes y sesiones propias. */
    @BeforeEach
    void prepare() throws Exception {
        deleteRows();
        adminId = createUser(ADMIN_EMAIL, "ADMIN");
        otherAdminId = createUser(OTHER_ADMIN_EMAIL, "ADMIN");
        standardId = createUser(STANDARD_EMAIL, "STANDARD");
        otherStandardId = createUser(OTHER_STANDARD_EMAIL, "STANDARD");
        admin = token(ADMIN_EMAIL);
        otherAdmin = token(OTHER_ADMIN_EMAIL);
        standard = token(STANDARD_EMAIL);
        otherStandard = token(OTHER_STANDARD_EMAIL);
    }

    @AfterEach
    void cleanUp() {
        deleteRows();
    }

    private void deleteRows() {
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM permission_requests");
        for (UUID order : createdOrders) {
            jdbc.update("DELETE FROM purchase_orders WHERE id = ?", order);
        }
        createdOrders.clear();
        for (String email : EMAILS) {
            jdbc.update("DELETE FROM auth_sessions WHERE user_id IN (SELECT id FROM users WHERE email_normalized = ?)",
                    email);
            jdbc.update("DELETE FROM users WHERE email_normalized = ?", email);
        }
    }

    // ---- 1: crear y listar (RF-GP-01, RF-GP-02) ----

    @Test
    void aStandardCreatesPendingAdminsSeeAllAndStandardsOnlyTheirOwn() throws Exception {
        MvcResult created = send(json(post(BASE), CREATE_BODY + " "), standard);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getContentAsString()).contains("\"status\":\"PENDING\"");
        UUID own = id(created);
        UUID foreign = createRequest(otherStandard);

        Map<String, Object> row = requestRow(own);
        assertThat(row.get("requested_by")).isEqualTo(standardId);
        assertThat(row.get("status")).isEqualTo("PENDING");

        String global = send(get(BASE + "/pending"), admin).getResponse().getContentAsString();
        assertThat(global).contains(own.toString(), foreign.toString(), standardId.toString())
                .doesNotContain("reason").doesNotContain("decided");
        String scoped = send(get(BASE + "/pending").param("userId", otherStandardId.toString()), standard)
                .getResponse().getContentAsString();
        assertThat(scoped).contains(own.toString()).doesNotContain(foreign.toString())
                .doesNotContain(otherStandardId.toString());
    }

    @Test
    void forgedIdentityFieldsInTheBodyAreIgnored() throws Exception {
        UUID created = id(send(json(post(BASE), "{\"permissionCode\":\"PURCHASE_ORDER_CREATE\",\"requestedBy\":\""
                + adminId + "\",\"userId\":\"" + adminId + "\",\"role\":\"ADMIN\",\"status\":\"APPLIED\"}"), standard));

        Map<String, Object> row = requestRow(created);
        assertThat(row.get("requested_by")).isEqualTo(standardId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM user_permissions")).isZero();
    }

    // ---- 2: aprobar aplica y desbloquea la orden (RF-GP-03, RF-GP-05) ----

    @Test
    void anOrderIsForbiddenUntilTheApprovalAppliesTheGrant() throws Exception {
        long ordersBefore = count("SELECT count(*) FROM purchase_orders");
        assertProblem(send(json(post(ORDERS), "{}"), standard), 403);
        assertProblem(send(json(post(ORDERS), "{}"), admin), 403);
        assertThat(count("SELECT count(*) FROM purchase_orders")).isEqualTo(ordersBefore);

        UUID request = createRequest(standard);
        assertThat(send(post(BASE + "/" + request + "/approve"), admin).getResponse().getStatus()).isEqualTo(204);

        Map<String, Object> row = requestRow(request);
        assertThat(row.get("status")).isEqualTo("APPLIED");
        assertThat(row.get("decided_by")).isEqualTo(adminId);
        assertThat(row.get("applied_by")).isEqualTo(adminId);
        assertThat(row.get("decided_at")).isNotNull().isEqualTo(row.get("applied_at"));
        List<Map<String, Object>> grants = jdbc.queryForList("SELECT * FROM user_permissions");
        assertThat(grants).singleElement().satisfies(grant -> {
            assertThat(grant.get("user_id")).isEqualTo(standardId);
            assertThat(grant.get("permission_code")).isEqualTo("PURCHASE_ORDER_CREATE");
            assertThat(grant.get("source_request_id")).isEqualTo(request);
        });

        MvcResult order = send(json(post(ORDERS), "{\"supplierId\":\"" + UUID.randomUUID() + "\"}"), standard);
        assertThat(order.getResponse().getStatus()).isEqualTo(201);
        createdOrders.add(id(order));
        assertThat(jdbc.queryForObject("SELECT state FROM purchase_orders WHERE id = ?", String.class, id(order)))
                .isEqualTo("DRAFT");
        assertProblem(send(json(post(ORDERS), "{}"), admin), 403);
    }

    // ---- 3: rechazar (RF-GP-04) ----

    @Test
    void aRejectionNeedsAWrittenReasonAndKeepsItLiteral() throws Exception {
        UUID withoutReason = createRequest(standard);
        Map<String, Object> before = requestRow(withoutReason);
        for (String body : new String[]{"{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}"}) {
            assertProblem(send(json(post(BASE + "/" + withoutReason + "/reject"), body), admin), 400);
        }
        assertProblem(send(post(BASE + "/" + withoutReason + "/reject"), admin), 400);
        assertThat(requestRow(withoutReason)).isEqualTo(before);

        UUID rejected = createRequest(otherStandard);
        MvcResult result = send(json(post(BASE + "/" + rejected + "/reject"), "{\"reason\":\"" + REASON + "\"}"),
                admin);
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        Map<String, Object> row = requestRow(rejected);
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("rejection_reason")).isEqualTo(REASON);
        assertThat(row.get("decided_by")).isEqualTo(adminId);
        assertThat(count("SELECT count(*) FROM user_permissions")).isZero();
    }

    // ---- 4: segunda resolución, auto-resolución, inexistente y rol (RF-GP-06, RF-GP-07, RD-06) ----

    @Test
    void aSecondApprovalIs409AndChangesNothing() throws Exception {
        UUID request = createRequest(standard);
        assertThat(send(post(BASE + "/" + request + "/approve"), admin).getResponse().getStatus()).isEqualTo(204);
        Map<String, Object> applied = requestRow(request);

        assertProblem(send(post(BASE + "/" + request + "/approve"), otherAdmin), 409);
        assertProblem(send(json(post(BASE + "/" + request + "/reject"), "{\"reason\":\"late\"}"), otherAdmin), 409);

        assertThat(requestRow(request)).isEqualTo(applied);
        assertThat(count("SELECT count(*) FROM user_permissions")).isEqualTo(1);
    }

    @Test
    void anAdminCannotResolveItsOwnRequestButAnotherAdminCan() throws Exception {
        UUID own = createRequest(admin);
        Map<String, Object> before = requestRow(own);

        String approve = assertProblem(send(post(BASE + "/" + own + "/approve"), admin), 409);
        assertProblem(send(json(post(BASE + "/" + own + "/reject"), "{\"reason\":\"" + REASON + "\"}"), admin), 409);
        assertThat(approve).doesNotContain("self").doesNotContain("requester");
        assertThat(requestRow(own)).isEqualTo(before);

        assertThat(send(post(BASE + "/" + own + "/approve"), otherAdmin).getResponse().getStatus()).isEqualTo(204);
        assertThat(requestRow(own).get("status")).isEqualTo("APPLIED");
    }

    @Test
    void unknownMalformedAndUnauthorizedResolutionsAreControlled() throws Exception {
        UUID request = createRequest(standard);
        Map<String, Object> before = requestRow(request);

        assertProblem(send(post(BASE + "/" + UUID.randomUUID() + "/approve"), admin), 404);
        assertProblem(send(post(BASE + "/not-a-uuid/approve"), admin), 400);
        assertProblem(send(post(BASE + "/" + request + "/approve"), standard), 403);
        assertProblem(send(json(post(BASE + "/" + request + "/reject"), "{\"reason\":\"x\"}"), otherStandard), 403);
        assertProblem(send(post(BASE + "/" + request + "/approve"), null), 401);
        assertProblem(send(get(BASE + "/pending"), "not-a-real-token"), 401);
        assertProblem(send(json(post(BASE), "{\"permissionCode\":\"SUPERUSER\"}"), standard), 400);

        jdbc.update("UPDATE users SET role = 'STANDARD' WHERE id = ?", adminId);
        assertProblem(send(post(BASE + "/" + request + "/approve"), admin), 403);

        assertThat(requestRow(request)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM user_permissions")).isZero();
    }

    // ---- utilidades ----

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
        MvcResult result = mvc.perform(json(post("/api/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).as("login").isEqualTo(200);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private UUID createRequest(String bearer) throws Exception {
        MvcResult result = send(json(post(BASE), CREATE_BODY), bearer);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return id(result);
    }

    private static UUID id(MvcResult result) throws Exception {
        Matcher m = ID.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return UUID.fromString(m.group(1));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn();
    }

    /** Comprueba estado y ProblemDetail; devuelve el cuerpo para aserciones adicionales. */
    private static String assertProblem(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as("problem+json for " + status).isTrue();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"status\":" + status).doesNotContain("Exception").doesNotContainIgnoringCase("select ");
        return body;
    }

    private Map<String, Object> requestRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM permission_requests WHERE id = ?", id);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
