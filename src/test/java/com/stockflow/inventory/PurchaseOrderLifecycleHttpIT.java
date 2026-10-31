package com.stockflow.inventory;

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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P3-F1 de punta a punta: login real, Bearer real, SecurityConfig, controlador, casos de uso y PostgreSQL. Las órdenes
 * se siembran con SQL para fijar el estado de partida; las transiciones solo se ejecutan por HTTP.
 */
class PurchaseOrderLifecycleHttpIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String ADMIN_EMAIL = "po-http-admin@example.test";
    private static final String STANDARD_EMAIL = "po-http-standard@example.test";
    private static final List<String> EMAILS = List.of(ADMIN_EMAIL, STANDARD_EMAIL);
    private static final String ORDERS = "/api/v1/purchase-orders/";
    private static final Instant CREATED_AT = Instant.parse("2026-02-03T04:05:06.789Z");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final List<UUID> seededOrders = new ArrayList<>();

    private UUID adminId;
    private String admin;
    private String standard;

    @BeforeEach
    void prepare() throws Exception {
        deleteRows();
        adminId = createUser(ADMIN_EMAIL, "ADMIN");
        createUser(STANDARD_EMAIL, "STANDARD");
        admin = token(ADMIN_EMAIL);
        standard = token(STANDARD_EMAIL);
    }

    @AfterEach
    void cleanUp() {
        deleteRows();
    }

    private void deleteRows() {
        for (UUID order : seededOrders) {
            jdbc.update("DELETE FROM purchase_orders WHERE id = ?", order);
        }
        seededOrders.clear();
        for (String email : EMAILS) {
            jdbc.update("DELETE FROM auth_sessions WHERE user_id IN (SELECT id FROM users WHERE email_normalized = ?)",
                    email);
            jdbc.update("DELETE FROM users WHERE email_normalized = ?", email);
        }
    }

    @Test
    void aStandardSubmitsAndAnAdminApprovesAndReceivesAndTheStateSurvivesANewConnection() throws Exception {
        // Arrange
        UUID supplier = UUID.randomUUID();
        UUID order = seed("DRAFT", supplier);

        // Act
        int submitted = status(transition(order, "submit", standard));
        String afterSubmit = state(order);
        int approved = status(transition(order, "approve", admin));
        String afterApprove = state(order);
        int received = status(transition(order, "receive", admin));

        // Assert
        assertThat(List.of(submitted, approved, received)).containsOnly(204);
        assertThat(afterSubmit).isEqualTo("SUBMITTED");
        assertThat(afterApprove).isEqualTo("APPROVED");
        try (Connection fresh = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = fresh.prepareStatement(
                     "SELECT supplier_id, state, created_at FROM purchase_orders WHERE id = ?")) {
            select.setObject(1, order);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("state")).isEqualTo("RECEIVED");
                assertThat(rs.getObject("supplier_id", UUID.class)).isEqualTo(supplier);
                assertThat(rs.getObject("created_at", OffsetDateTime.class).toInstant()).isEqualTo(CREATED_AT);
            }
        }
    }

    /** Detector P3-3 de integración: sin proveedor, el envío es 409 y la fila sigue en DRAFT. */
    @Test
    void submittingWithoutSupplierIs409AndTheRowStaysDraft() throws Exception {
        // Arrange
        UUID order = seed("DRAFT", null);
        Map<String, Object> before = row(order);

        // Act
        MvcResult result = transition(order, "submit", standard);

        // Assert
        assertProblem(result, 409);
        assertThat(row(order)).isEqualTo(before);
    }

    @Test
    void cancellationFollowsTheRolePolicyOfTheLockedState() throws Exception {
        // Arrange
        UUID draft = seed("DRAFT", null);
        UUID submitted = seed("SUBMITTED", UUID.randomUUID());
        UUID approved = seed("APPROVED", UUID.randomUUID());

        // Act
        int draftByStandard = status(transition(draft, "cancel", standard));
        MvcResult submittedByStandard = transition(submitted, "cancel", standard);
        MvcResult approvedByStandard = transition(approved, "cancel", standard);
        String submittedAfterStandard = state(submitted);
        String approvedAfterStandard = state(approved);
        int submittedByAdmin = status(transition(submitted, "cancel", admin));
        int approvedByAdmin = status(transition(approved, "cancel", admin));

        // Assert
        assertThat(draftByStandard).isEqualTo(204);
        assertProblem(submittedByStandard, 403);
        assertProblem(approvedByStandard, 403);
        assertThat(submittedAfterStandard).isEqualTo("SUBMITTED");
        assertThat(approvedAfterStandard).isEqualTo("APPROVED");
        assertThat(List.of(submittedByAdmin, approvedByAdmin)).containsOnly(204);
        assertThat(List.of(state(draft), state(submitted), state(approved))).containsOnly("CANCELLED");
    }

    @Test
    void aStandardCannotApproveOrReceiveAndTheRowIsUntouched() throws Exception {
        // Arrange
        UUID submitted = seed("SUBMITTED", UUID.randomUUID());
        UUID approved = seed("APPROVED", UUID.randomUUID());
        Map<String, Object> submittedBefore = row(submitted);
        Map<String, Object> approvedBefore = row(approved);

        // Act
        MvcResult approve = transition(submitted, "approve", standard);
        MvcResult receive = transition(approved, "receive", standard);

        // Assert
        assertProblem(approve, 403);
        assertProblem(receive, 403);
        assertThat(row(submitted)).isEqualTo(submittedBefore);
        assertThat(row(approved)).isEqualTo(approvedBefore);
    }

    @Test
    void forbiddenAndTerminalTransitionsAre409AndChangeNothing() throws Exception {
        // Arrange
        UUID submitted = seed("SUBMITTED", UUID.randomUUID());
        UUID received = seed("RECEIVED", UUID.randomUUID());
        UUID cancelled = seed("CANCELLED", UUID.randomUUID());
        Map<String, Object> submittedBefore = row(submitted);
        Map<String, Object> receivedBefore = row(received);
        Map<String, Object> cancelledBefore = row(cancelled);

        // Act / Assert
        assertProblem(transition(submitted, "receive", admin), 409);
        assertProblem(transition(submitted, "submit", admin), 409);
        for (String action : List.of("submit", "approve", "receive", "cancel")) {
            assertProblem(transition(received, action, admin), 409);
            assertProblem(transition(cancelled, action, admin), 409);
        }

        // Assert
        assertThat(row(submitted)).isEqualTo(submittedBefore);
        assertThat(row(received)).isEqualTo(receivedBefore);
        assertThat(row(cancelled)).isEqualTo(cancelledBefore);
    }

    @Test
    void unknownMalformedUnauthenticatedAndDowngradedRequestsAreControlled() throws Exception {
        // Arrange
        UUID submitted = seed("SUBMITTED", UUID.randomUUID());
        Map<String, Object> before = row(submitted);

        // Act / Assert
        assertProblem(transition(UUID.randomUUID(), "approve", admin), 404);
        assertProblem(transition(UUID.randomUUID(), "submit", standard), 404);
        assertProblem(send(post(ORDERS + "not-a-uuid/approve"), admin), 400);
        assertProblem(transition(submitted, "approve", null), 401);
        assertProblem(transition(submitted, "approve", "not-a-real-token"), 401);

        jdbc.update("UPDATE users SET role = 'STANDARD' WHERE id = ?", adminId);
        assertProblem(transition(submitted, "approve", admin), 403);

        // Assert
        assertThat(row(submitted)).isEqualTo(before);
    }

    @Test
    void forgedBodyFieldsDoNotChooseTheActorOrTheTargetState() throws Exception {
        // Arrange
        UUID order = seed("SUBMITTED", UUID.randomUUID());
        String forged = "{\"userId\":\"" + adminId + "\",\"role\":\"ADMIN\",\"status\":\"RECEIVED\"}";

        // Act
        MvcResult result = send(post(ORDERS + order + "/approve").contentType(MediaType.APPLICATION_JSON)
                .content(forged), standard);

        // Assert
        assertProblem(result, 403);
        assertThat(state(order)).isEqualTo("SUBMITTED");
    }

    // ---- utilidades ----

    private UUID seed(String state, UUID supplier) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_orders (id, supplier_id, state, created_at) VALUES (?, ?, ?, ?)",
                id, supplier, state, OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
        seededOrders.add(id);
        return id;
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
        assertThat(result.getResponse().getStatus()).as("login").isEqualTo(200);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private MvcResult transition(UUID order, String action, String bearer) throws Exception {
        return send(post(ORDERS + order + "/" + action), bearer);
    }

    private MvcResult send(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static void assertProblem(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(MediaType.parseMediaType(result.getResponse().getContentType())
                .isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).as("problem+json for " + status).isTrue();
        assertThat(result.getResponse().getContentAsString()).contains("\"status\":" + status)
                .doesNotContain("Exception").doesNotContainIgnoringCase("select ").doesNotContain("SUBMITTED")
                .doesNotContain("supplier");
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM purchase_orders WHERE id = ?", id);
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT state FROM purchase_orders WHERE id = ?", String.class, id);
    }
}
