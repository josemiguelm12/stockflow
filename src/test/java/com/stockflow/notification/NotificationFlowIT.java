package com.stockflow.notification;

import com.stockflow.notification.application.EncryptedPayload;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RF-NOT-01…07 de punta a punta: login real, Bearer real, SecurityConfig, casos de uso, outbox cifrado y PostgreSQL.
 * Ninguna petición aporta actor ni destinatario: salen de la sesión y de la identidad persistida.
 */
class NotificationFlowIT extends AbstractPostgresIT {

    private static final String PASSWORD = "Passw0rd-secret";
    private static final String PERMISSIONS = "/api/v1/permission-requests";
    private static final String ORDERS = "/api/v1/purchase-orders";
    private static final String NOTIFICATIONS = "/api/v1/notifications";
    private static final String CREATE_BODY = "{\"permissionCode\":\"PURCHASE_ORDER_CREATE\"}";
    private static final Pattern ID = Pattern.compile("\"id\":\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"accessToken\":\"([^\"]+)\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutboxPayloadCipher cipher;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private RecordingEmailSender sender;
    @Autowired
    private MutableClock clock;

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    private UUID admin1Id;
    private UUID admin2Id;
    private UUID disabledAdminId;
    private UUID standard1Id;
    private UUID standard2Id;
    private String admin1;
    private String admin2;
    private String standard1;
    private String standard2;

    @BeforeEach
    void prepare() throws Exception {
        sender.reset();
        admin1Id = createUser("nf-admin-1@example.test", "ADMIN", "ACTIVE");
        admin2Id = createUser("nf-admin-2@example.test", "ADMIN", "ACTIVE");
        disabledAdminId = createUser("nf-admin-off@example.test", "ADMIN", "DISABLED");
        standard1Id = createUser("nf-standard-1@example.test", "STANDARD", "ACTIVE");
        standard2Id = createUser("nf-standard-2@example.test", "STANDARD", "ACTIVE");
        admin1 = token("nf-admin-1@example.test");
        admin2 = token("nf-admin-2@example.test");
        standard1 = token("nf-standard-1@example.test");
        standard2 = token("nf-standard-2@example.test");
    }

    @AfterEach
    void cleanUp() {
        dropFaultTrigger();
        sender.reset();
    }

    // ---- RF-NOT-01 ----

    @Test
    void creatingARequestNotifiesExactlyTheActiveAdminsInternallyAndByEmail() throws Exception {
        UUID request = createRequest(standard1);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, type, resource_type, resource_id, read_at FROM notifications ORDER BY user_id");
        assertThat(rows).extracting(r -> r.get("user_id")).containsExactlyInAnyOrder(admin1Id, admin2Id);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("type")).isEqualTo("PERMISSION_REQUEST_CREATED");
            assertThat(r.get("resource_type")).isEqualTo("PERMISSION_REQUEST");
            assertThat(r.get("resource_id")).isEqualTo(request);
            assertThat(r.get("read_at")).isNull();
        });
        assertThat(emailRecipients("PERMISSION_REQUEST_CREATED"))
                .containsExactlyInAnyOrder("nf-admin-1@example.test", "nf-admin-2@example.test");
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id IN (?, ?)", standard1Id, disabledAdminId))
                .as("neither STANDARD accounts nor inactive ADMINs").isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE recipient_email IN (?, ?)",
                "nf-standard-1@example.test", "nf-admin-off@example.test")).isZero();
    }

    @Test
    void aSwitchedOffPreferenceSuppressesOnlyTheEmailNeverTheInternalNotice() throws Exception {
        assertThat(preference(admin2)).as("enabled by default").isTrue();
        assertThat(count("SELECT count(*) FROM user_notification_preferences")).isZero();
        setPreference(admin2, false);

        createRequest(standard1);

        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", admin2Id)).isOne();
        assertThat(emailRecipients("PERMISSION_REQUEST_CREATED")).containsExactly("nf-admin-1@example.test");
    }

    @Test
    void anAdminRequestingAPermissionAlsoNotifiesItself() throws Exception {
        createRequest(admin1);

        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", admin1Id)).isOne();
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", admin2Id)).isOne();
    }

    // ---- RF-NOT-02 ----

    @Test
    void approvingNotifiesOnlyTheRequester() throws Exception {
        UUID request = createRequest(standard1);
        clearNotificationsAndEmails();

        assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), admin1).getResponse().getStatus())
                .isEqualTo(204);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id, type, title FROM notifications");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("user_id")).isEqualTo(standard1Id);
        assertThat(rows.get(0).get("type")).isEqualTo("PERMISSION_REQUEST_APPROVED");
        assertThat(emailRecipients("PERMISSION_REQUEST_APPROVED")).containsExactly("nf-standard-1@example.test");
        assertThat(count("SELECT count(*) FROM outbound_emails")).isOne();
    }

    @Test
    void rejectingNotifiesOnlyTheRequesterWithTheLiteralReasonInBothChannels() throws Exception {
        UUID request = createRequest(standard1);
        clearNotificationsAndEmails();
        String reason = "  Falta justificación del área  ";

        assertThat(send(json(post(PERMISSIONS + "/" + request + "/reject"), "{\"reason\":\"" + reason + "\"}"), admin1)
                .getResponse().getStatus()).isEqualTo(204);

        Map<String, Object> notice = jdbc.queryForMap("SELECT user_id, type, message FROM notifications");
        assertThat(notice.get("user_id")).isEqualTo(standard1Id);
        assertThat(notice.get("type")).isEqualTo("PERMISSION_REQUEST_REJECTED");
        assertThat((String) notice.get("message")).endsWith(reason);
        assertThat(decryptedPayload("PERMISSION_REQUEST_REJECTED", "nf-standard-1@example.test")).contains(reason);
        assertThat(emailRecipients("PERMISSION_REQUEST_REJECTED")).containsExactly("nf-standard-1@example.test");
    }

    @Test
    void anInvalidRepeatedOrUnauthorizedDecisionNeverNotifies() throws Exception {
        UUID request = createRequest(standard1);
        clearNotificationsAndEmails();

        assertThat(send(json(post(PERMISSIONS + "/" + request + "/reject"), "{\"reason\":\"   \"}"), admin1)
                .getResponse().getStatus()).as("blank reason").isEqualTo(400);
        assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), standard2).getResponse().getStatus())
                .as("not an admin").isEqualTo(403);
        assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), standard1).getResponse().getStatus())
                .as("standard requester").isEqualTo(403);
        assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), null).getResponse().getStatus())
                .as("anonymous").isEqualTo(401);
        assertThat(send(post(PERMISSIONS + "/" + UUID.randomUUID() + "/approve"), admin1).getResponse().getStatus())
                .as("unknown request").isEqualTo(404);
        assertThat(count("SELECT count(*) FROM notifications")).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();

        assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), admin1).getResponse().getStatus())
                .isEqualTo(204);
        long afterFirst = count("SELECT count(*) FROM notifications");
        assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), admin2).getResponse().getStatus())
                .as("repeated approval").isEqualTo(409);
        assertThat(send(json(post(PERMISSIONS + "/" + request + "/reject"), "{\"reason\":\"late\"}"), admin2)
                .getResponse().getStatus()).as("reject after approval").isEqualTo(409);

        assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(afterFirst).isOne();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isOne();
    }

    @Test
    void aSwitchedOffRequesterPreferenceKeepsTheDecisionNoticeButQueuesNoEmail() throws Exception {
        UUID request = createRequest(standard1);
        clearNotificationsAndEmails();
        setPreference(standard1, false);

        send(post(PERMISSIONS + "/" + request + "/approve"), admin1);

        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", standard1Id)).isOne();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();
    }

    // ---- RF-NOT-07: la preferencia no toca el correo esencial ----

    @Test
    void activationAndPasswordRecoveryStillQueueEmailWithTheFunctionalPreferenceOff() throws Exception {
        setPreference(standard1, false);

        mvc.perform(json(post("/api/v1/auth/password/forgot"), "{\"email\":\"nf-standard-1@example.test\"}"))
                .andReturn();
        mvc.perform(json(post("/api/v1/auth/register"),
                "{\"email\":\"nf-new-user@example.test\",\"password\":\"" + PASSWORD + "\"}")).andReturn();

        assertThat(count("SELECT count(*) FROM outbound_emails WHERE template_key = 'PASSWORD_RESET'"
                + " AND recipient_email = ?", "nf-standard-1@example.test")).isOne();
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE template_key = 'ACTIVATION'"
                + " AND recipient_email = ?", "nf-new-user@example.test")).isOne();
        assertThat(count("SELECT count(*) FROM notifications")).as("no functional notice for essential flows").isZero();
    }

    @Test
    void thePreferenceEndpointsReadAndWriteOnlyTheCallersOwnSetting() throws Exception {
        assertThat(preference(standard1)).isTrue();

        MvcResult updated = send(json(patch(NOTIFICATIONS + "/preferences"),
                "{\"functionalEmailEnabled\":false,\"userId\":\"" + standard2Id + "\"}"), standard1);

        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(updated.getResponse().getContentAsString()).contains("\"functionalEmailEnabled\":false");
        assertThat(preference(standard1)).isFalse();
        assertThat(preference(standard2)).as("another user is unaffected").isTrue();
        assertThat(count("SELECT count(*) FROM user_notification_preferences WHERE user_id = ?", standard2Id)).isZero();
        setPreference(standard1, true);
        assertThat(preference(standard1)).isTrue();
        assertThat(send(json(patch(NOTIFICATIONS + "/preferences"), "{}"), standard1).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(send(get(NOTIFICATIONS + "/preferences"), null).getResponse().getStatus()).isEqualTo(401);
    }

    // ---- RF-NOT-03 ----

    @Test
    void approvingASubmittedOrderNotifiesItsOwnerAndRecordsTheDates() throws Exception {
        UUID order = createOrderAs(standard1, standard1Id);
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM purchase_orders WHERE id = ?", UUID.class, order))
                .isEqualTo(standard1Id);
        assertThat(jdbc.queryForMap("SELECT submitted_at, approved_at FROM purchase_orders WHERE id = ?", order)
                .values()).containsOnlyNulls();
        assertThat(send(post(ORDERS + "/" + order + "/submit"), standard1).getResponse().getStatus()).isEqualTo(204);
        assertThat(timestamp(order, "submitted_at")).isNotNull();
        assertThat(timestamp(order, "approved_at")).isNull();
        clearNotificationsAndEmails();

        assertThat(send(post(ORDERS + "/" + order + "/approve"), admin2).getResponse().getStatus()).isEqualTo(204);

        assertThat(timestamp(order, "approved_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM purchase_orders WHERE id = ?", UUID.class, order))
                .as("the approver never becomes the owner").isEqualTo(standard1Id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT user_id, type, resource_type, resource_id FROM notifications");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("user_id")).isEqualTo(standard1Id);
        assertThat(rows.get(0).get("type")).isEqualTo("PURCHASE_ORDER_APPROVED");
        assertThat(rows.get(0).get("resource_id")).isEqualTo(order);
        assertThat(emailRecipients("PURCHASE_ORDER_APPROVED")).containsExactly("nf-standard-1@example.test");
    }

    @Test
    void aSwitchedOffOwnerPreferenceKeepsTheOrderNoticeButQueuesNoEmail() throws Exception {
        UUID order = createOrderAs(standard1, standard1Id);
        send(post(ORDERS + "/" + order + "/submit"), standard1);
        setPreference(standard1, false);
        clearNotificationsAndEmails();

        send(post(ORDERS + "/" + order + "/approve"), admin1);

        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", standard1Id)).isOne();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();
    }

    @Test
    void invalidOrRepeatedOrderTransitionsNeverNotify() throws Exception {
        UUID order = createOrderAs(standard1, standard1Id);
        clearNotificationsAndEmails();

        assertThat(send(post(ORDERS + "/" + order + "/approve"), admin1).getResponse().getStatus())
                .as("DRAFT cannot be approved").isEqualTo(409);
        assertThat(timestamp(order, "approved_at")).isNull();
        send(post(ORDERS + "/" + order + "/submit"), standard1);
        assertThat(send(post(ORDERS + "/" + order + "/approve"), standard1).getResponse().getStatus())
                .as("STANDARD cannot approve").isEqualTo(403);
        assertThat(count("SELECT count(*) FROM notifications")).isZero();

        assertThat(send(post(ORDERS + "/" + order + "/approve"), admin1).getResponse().getStatus()).isEqualTo(204);
        Instant approvedAt = timestamp(order, "approved_at");
        assertThat(send(post(ORDERS + "/" + order + "/approve"), admin2).getResponse().getStatus())
                .as("repeated approval").isEqualTo(409);

        assertThat(count("SELECT count(*) FROM notifications")).isOne();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isOne();
        assertThat(timestamp(order, "approved_at")).as("approved_at is not rewritten").isEqualTo(approvedAt);
    }

    @Test
    void aHistoricalOrderWithoutOwnerIsApprovedWithoutNoticeNorAttribution() throws Exception {
        UUID historic = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_orders (id, supplier_id, state, created_at) VALUES (?, ?, 'SUBMITTED', now())",
                historic, UUID.randomUUID());
        clearNotificationsAndEmails();

        assertThat(send(post(ORDERS + "/" + historic + "/approve"), admin1).getResponse().getStatus())
                .isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT state FROM purchase_orders WHERE id = ?", String.class, historic))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM purchase_orders WHERE id = ?", UUID.class, historic))
                .isNull();
        assertThat(timestamp(historic, "submitted_at")).as("no invented submission date").isNull();
        assertThat(count("SELECT count(*) FROM notifications")).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();
    }

    // ---- Regresión F1-T03.1: solo RF-NOT-01 filtra por ACTIVE; solicitante/propietario reciben aviso siempre ----

    @Test
    void inactiveAdminsNeverReceiveTheCreationNoticeWhateverTheirInactiveState() throws Exception {
        UUID pendingAdmin = createUser("nf-admin-pending@example.test", "ADMIN", "PENDING_ACTIVATION");

        createRequest(standard1);

        assertThat(count("SELECT count(*) FROM notifications WHERE user_id IN (?, ?)", disabledAdminId, pendingAdmin))
                .isZero();
        assertThat(emailRecipients("PERMISSION_REQUEST_CREATED"))
                .containsExactlyInAnyOrder("nf-admin-1@example.test", "nf-admin-2@example.test");
    }

    @Test
    void aDisabledRequesterStillReceivesTheApprovalNoticeAndEmail() throws Exception {
        UUID request = createRequest(standard1);
        clearNotificationsAndEmails();
        deactivate(standard1Id);

        assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), admin1).getResponse().getStatus())
                .isEqualTo(204);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id, type FROM notifications");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("user_id")).isEqualTo(standard1Id);
        assertThat(rows.get(0).get("type")).isEqualTo("PERMISSION_REQUEST_APPROVED");
        assertThat(emailRecipients("PERMISSION_REQUEST_APPROVED")).containsExactly("nf-standard-1@example.test");
    }

    @Test
    void aDisabledRequesterStillReceivesTheRejectionWithTheLiteralReason() throws Exception {
        UUID request = createRequest(standard1);
        clearNotificationsAndEmails();
        deactivate(standard1Id);
        String reason = "  Cuenta en revisión: motivo literal  ";

        assertThat(send(json(post(PERMISSIONS + "/" + request + "/reject"), "{\"reason\":\"" + reason + "\"}"), admin1)
                .getResponse().getStatus()).isEqualTo(204);

        Map<String, Object> notice = jdbc.queryForMap("SELECT user_id, type, message FROM notifications");
        assertThat(notice.get("user_id")).isEqualTo(standard1Id);
        assertThat(notice.get("type")).isEqualTo("PERMISSION_REQUEST_REJECTED");
        assertThat((String) notice.get("message")).endsWith(reason);
        assertThat(decryptedPayload("PERMISSION_REQUEST_REJECTED", "nf-standard-1@example.test")).contains(reason);
        assertThat(count("SELECT count(*) FROM notifications")).isOne();
    }

    @Test
    void aDisabledOrderOwnerStillReceivesTheApprovalNotice() throws Exception {
        UUID order = createOrderAs(standard1, standard1Id);
        send(post(ORDERS + "/" + order + "/submit"), standard1);
        clearNotificationsAndEmails();
        deactivate(standard1Id);

        assertThat(send(post(ORDERS + "/" + order + "/approve"), admin2).getResponse().getStatus()).isEqualTo(204);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id, type, resource_id FROM notifications");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("user_id")).isEqualTo(standard1Id);
        assertThat(rows.get(0).get("type")).isEqualTo("PURCHASE_ORDER_APPROVED");
        assertThat(rows.get(0).get("resource_id")).isEqualTo(order);
        assertThat(emailRecipients("PURCHASE_ORDER_APPROVED")).containsExactly("nf-standard-1@example.test");
    }

    @Test
    void theEmailPreferenceStillAppliesToDisabledRecipients() throws Exception {
        UUID request = createRequest(standard1);
        UUID order = createOrderAs(standard2, standard2Id);
        send(post(ORDERS + "/" + order + "/submit"), standard2);
        setPreference(standard1, false);
        setPreference(standard2, false);
        clearNotificationsAndEmails();
        deactivate(standard1Id);
        deactivate(standard2Id);

        send(post(PERMISSIONS + "/" + request + "/approve"), admin1);
        send(post(ORDERS + "/" + order + "/approve"), admin1);

        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", standard1Id)).isOne();
        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", standard2Id)).isOne();
        assertThat(count("SELECT count(*) FROM outbound_emails")).as("preference off: internal notice only").isZero();
    }

    @Test
    void receivingANoticeNeverRestoresAccessToADisabledUser() throws Exception {
        UUID request = createRequest(standard1);
        deactivate(standard1Id);

        send(post(PERMISSIONS + "/" + request + "/approve"), admin1);

        assertThat(count("SELECT count(*) FROM notifications WHERE user_id = ?", standard1Id)).isPositive();
        assertThat(jdbc.queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, standard1Id))
                .isEqualTo("DISABLED");
        assertThat(send(get(NOTIFICATIONS), standard1).getResponse().getStatus())
                .as("the previous session stays invalid").isEqualTo(401);
        assertThat(send(post(NOTIFICATIONS + "/" + UUID.randomUUID() + "/read"), standard1).getResponse().getStatus())
                .isEqualTo(401);
        MvcResult login = mvc.perform(json(post("/api/v1/auth/login"),
                "{\"email\":\"nf-standard-1@example.test\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(login.getResponse().getStatus()).as("login stays refused").isBetween(400, 403);
        assertThat(login.getResponse().getContentAsString()).doesNotContain("accessToken");
    }

    // ---- RF-NOT-04 / RF-NOT-05 ----

    @Test
    void eachUserOnlySeesItsOwnNotificationsWithReadFiltersAndAForeignUserIdChangesNothing() throws Exception {
        UUID a = seedNotification(standard1Id, "2026-01-01T00:00:01Z", null);
        UUID b = seedNotification(standard1Id, "2026-01-01T00:00:02Z", "2026-01-01T00:10:00Z");
        UUID c = seedNotification(standard2Id, "2026-01-01T00:00:03Z", null);

        String all = body(send(get(NOTIFICATIONS), standard1));
        String unread = body(send(get(NOTIFICATIONS).param("read", "false"), standard1));
        String read = body(send(get(NOTIFICATIONS).param("read", "true"), standard1));
        String spoofed = body(send(get(NOTIFICATIONS).param("userId", standard2Id.toString()), standard1));

        assertThat(all).contains(a.toString(), b.toString()).doesNotContain(c.toString())
                .contains("\"totalElements\":2");
        assertThat(unread).contains(a.toString()).doesNotContain(b.toString(), c.toString())
                .contains("\"totalElements\":1");
        assertThat(read).contains(b.toString()).doesNotContain(a.toString(), c.toString())
                .contains("\"readAt\":\"2026-01-01T00:10:00Z\"");
        assertThat(spoofed).contains(a.toString(), b.toString()).doesNotContain(c.toString());
        assertThat(body(send(get(NOTIFICATIONS), standard2))).contains(c.toString())
                .doesNotContain(a.toString(), b.toString());
        assertThat(send(get(NOTIFICATIONS).param("read", "maybe"), standard1).getResponse().getStatus()).isEqualTo(400);
        assertThat(send(get(NOTIFICATIONS).param("size", "101"), standard1).getResponse().getStatus()).isEqualTo(400);
        assertThat(send(get(NOTIFICATIONS).param("page", "-1"), standard1).getResponse().getStatus()).isEqualTo(400);
        assertThat(send(get(NOTIFICATIONS), null).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void markingReadStoresReadAtOnceAndRepeatingKeepsTheOriginalValue() throws Exception {
        UUID own = seedNotification(standard1Id, "2025-12-31T00:00:00Z", null);
        assertThat(timestampOf(own)).isNull();

        MvcResult first = send(post(NOTIFICATIONS + "/" + own + "/read"), standard1);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        Instant storedFirst = timestampOf(own);
        assertThat(storedFirst).isNotNull();
        assertThat(first.getResponse().getContentAsString()).contains("\"read\":true").contains("\"readAt\":\"");

        // El reloj de la aplicación avanza: una segunda lectura no puede mover readAt.
        clock.advance(Duration.ofSeconds(2));
        MvcResult second = send(post(NOTIFICATIONS + "/" + own + "/read"), standard1);

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(timestampOf(own)).isEqualTo(storedFirst);
        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(body(send(get(NOTIFICATIONS).param("read", "true"), standard1))).contains(own.toString());
    }

    @Test
    void aForeignOrMissingNotificationIs404AndNothingChanges() throws Exception {
        UUID foreign = seedNotification(standard2Id, "2026-01-01T00:00:01Z", null);

        MvcResult attempt = send(post(NOTIFICATIONS + "/" + foreign + "/read"), standard1);
        MvcResult missing = send(post(NOTIFICATIONS + "/" + UUID.randomUUID() + "/read"), standard1);

        assertThat(attempt.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(timestampOf(foreign)).isNull();
        assertThat(send(post(NOTIFICATIONS + "/" + foreign + "/read"), null).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(send(post(NOTIFICATIONS + "/not-a-uuid/read"), standard1).getResponse().getStatus())
                .isEqualTo(400);
    }

    // ---- RF-NOT-06 ----

    @Test
    void anOutboxFailureRollsBackTheDecisionTheNoticeAndTheEmailTogether() throws Exception {
        UUID request = createRequest(standard1);
        clearNotificationsAndEmails();
        installFaultTrigger();

        MvcResult approve = send(post(PERMISSIONS + "/" + request + "/approve"), admin1);
        MvcResult create = send(json(post(PERMISSIONS), CREATE_BODY), standard2);

        assertThat(approve.getResponse().getStatus()).isEqualTo(500);
        assertThat(approve.getResponse().getContentAsString()).doesNotContainIgnoringCase("select ")
                .doesNotContain("simulated").doesNotContain("Exception");
        assertThat(create.getResponse().getStatus()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT status FROM permission_requests WHERE id = ?", String.class, request))
                .as("the decision rolled back").isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM user_permissions")).isZero();
        assertThat(count("SELECT count(*) FROM permission_requests WHERE requested_by = ?", standard2Id))
                .as("the creation rolled back").isZero();
        assertThat(count("SELECT count(*) FROM notifications")).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();

        dropFaultTrigger();
        assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), admin1).getResponse().getStatus())
                .as("the system recovers once the fault is gone").isEqualTo(204);
    }

    @Test
    void anOrderApprovalRollsBackCompletelyWhenTheOutboxFails() throws Exception {
        UUID order = createOrderAs(standard1, standard1Id);
        send(post(ORDERS + "/" + order + "/submit"), standard1);
        clearNotificationsAndEmails();
        installFaultTrigger();

        assertThat(send(post(ORDERS + "/" + order + "/approve"), admin1).getResponse().getStatus()).isEqualTo(500);

        assertThat(jdbc.queryForObject("SELECT state FROM purchase_orders WHERE id = ?", String.class, order))
                .isEqualTo("SUBMITTED");
        assertThat(timestamp(order, "approved_at")).isNull();
        assertThat(count("SELECT count(*) FROM notifications")).isZero();
        assertThat(count("SELECT count(*) FROM outbound_emails")).isZero();
    }

    @Test
    void theApiNeverContactsSmtpAndALaterSmtpFailureKeepsTheEventAndTheNotice() throws Exception {
        UUID request = createRequest(standard1);
        send(post(PERMISSIONS + "/" + request + "/approve"), admin1);
        assertThat(sender.sent()).as("the web process never sends").isEmpty();
        long pending = count("SELECT count(*) FROM outbound_emails WHERE state = 'PENDING'");
        assertThat(pending).isEqualTo(3);

        sender.setFailing(true);
        OutboxDispatcher.Result failed = dispatcher.dispatchPending(50);

        assertThat(failed.sent()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM permission_requests WHERE id = ?", String.class, request))
                .isEqualTo("APPLIED");
        assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE state = 'PENDING'")).isEqualTo(pending);

        sender.setFailing(false);
        OutboxDispatcher.Result delivered = dispatcher.dispatchPending(50);

        assertThat(delivered.sent()).isEqualTo(3);
        assertThat(sender.sent()).extracting(RecordingEmailSender.Sent::recipient)
                .containsExactlyInAnyOrder("nf-admin-1@example.test", "nf-admin-2@example.test",
                        "nf-standard-1@example.test");
        assertThat(sender.sent()).filteredOn(s -> s.recipient().equals("nf-standard-1@example.test"))
                .singleElement().satisfies(s -> {
                    assertThat(s.subject()).isEqualTo("Solicitud de permiso aprobada");
                    assertThat(s.body()).contains("PURCHASE_ORDER_CREATE").contains("preferencias de notificaciones");
                });
        assertThat(count("SELECT count(*) FROM outbound_emails WHERE state = 'SENT' AND payload_ciphertext IS NULL"))
                .isEqualTo(3);
    }

    @Test
    void theFunctionalPayloadIsStoredEncryptedNeverInClear() throws Exception {
        createRequest(standard1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT payload_ciphertext, recipient_email, template_key FROM outbound_emails LIMIT 1");
        String raw = new String((byte[]) row.get("payload_ciphertext"), StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("Nueva solicitud").doesNotContain("PURCHASE_ORDER_CREATE");
        assertThat(jdbc.queryForObject("SELECT coalesce(string_agg(t::text, ' '), '') FROM outbound_emails t",
                String.class)).doesNotContain("Nueva solicitud").doesNotContain("PURCHASE_ORDER_CREATE");
        assertThat(decryptedPayload("PERMISSION_REQUEST_CREATED", "nf-admin-1@example.test"))
                .contains("Nueva solicitud de permiso");
    }

    // ---- utilidades ----

    private UUID createUser(String email, String role, String status) {
        UUID id = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.ofInstant(Instant.parse("2025-12-01T00:00:00Z"), ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                                   created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, email, bcrypt.encode(PASSWORD), role, status, at, at, at);
        return id;
    }

    private String token(String email) throws Exception {
        MvcResult result = mvc.perform(json(post("/api/v1/auth/login"),
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        assertThat(result.getResponse().getStatus()).as("login " + email).isEqualTo(200);
        Matcher m = ACCESS_TOKEN.matcher(result.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private UUID createRequest(String bearer) throws Exception {
        MvcResult result = send(json(post(PERMISSIONS), CREATE_BODY), bearer);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return id(result);
    }

    /** Concede PURCHASE_ORDER_CREATE por el flujo real (solicitud + aprobación) y crea una orden con proveedor. */
    private UUID createOrderAs(String bearer, UUID userId) throws Exception {
        if (count("SELECT count(*) FROM user_permissions WHERE user_id = ?", userId) == 0) {
            UUID request = createRequest(bearer);
            assertThat(send(post(PERMISSIONS + "/" + request + "/approve"), admin1).getResponse().getStatus())
                    .isEqualTo(204);
        }
        MvcResult created = send(json(post(ORDERS), "{\"supplierId\":\"" + UUID.randomUUID() + "\"}"), bearer);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID order = id(created);
        clearNotificationsAndEmails();
        return order;
    }

    /** Desactiva la cuenta como lo haría la administración; nada más cambia. */
    private void deactivate(UUID userId) {
        jdbc.update("UPDATE users SET account_status = 'DISABLED' WHERE id = ?", userId);
    }

    private void clearNotificationsAndEmails() {
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbound_emails");
    }

    private UUID seedNotification(UUID userId, String createdAt, String readAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notifications (id, user_id, type, event_key, title, message, resource_type, resource_id,
                                           created_at, read_at)
                VALUES (?, ?, 'PURCHASE_ORDER_APPROVED', ?, 'Titulo', 'Mensaje', 'PURCHASE_ORDER', ?, ?, ?)
                """, id, userId, "seed:" + id, UUID.randomUUID(), OffsetDateTime.parse(createdAt),
                readAt == null ? null : OffsetDateTime.parse(readAt));
        return id;
    }

    private boolean preference(String bearer) throws Exception {
        MvcResult result = send(get(NOTIFICATIONS + "/preferences"), bearer);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString().contains("\"functionalEmailEnabled\":true");
    }

    private void setPreference(String bearer, boolean enabled) throws Exception {
        assertThat(send(json(patch(NOTIFICATIONS + "/preferences"), "{\"functionalEmailEnabled\":" + enabled + "}"),
                bearer).getResponse().getStatus()).isEqualTo(200);
    }

    private List<String> emailRecipients(String template) {
        return jdbc.queryForList("SELECT recipient_email FROM outbound_emails WHERE template_key = ?", String.class,
                template);
    }

    private String decryptedPayload(String template, String recipient) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT payload_ciphertext, payload_iv, payload_key_id FROM outbound_emails
                WHERE template_key = ? AND recipient_email = ?
                """, template, recipient);
        return new String(cipher.decrypt(new EncryptedPayload((byte[]) row.get("payload_ciphertext"),
                (byte[]) row.get("payload_iv"), (String) row.get("payload_key_id"))), StandardCharsets.UTF_8);
    }

    private Instant timestamp(UUID order, String column) {
        OffsetDateTime value = jdbc.queryForObject("SELECT " + column + " FROM purchase_orders WHERE id = ?",
                OffsetDateTime.class, order);
        return value == null ? null : value.toInstant();
    }

    private Instant timestampOf(UUID notification) {
        OffsetDateTime value = jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?",
                OffsetDateTime.class, notification);
        return value == null ? null : value.toInstant();
    }

    /** Falla de persistencia real: el INSERT de un correo funcional lanza un error de PostgreSQL. */
    private void installFaultTrigger() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION p5_fail_functional_outbox() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'simulated outbox failure'; END; $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER p5_fail_functional_outbox BEFORE INSERT ON outbound_emails
                FOR EACH ROW WHEN (NEW.dedupe_key IS NOT NULL) EXECUTE FUNCTION p5_fail_functional_outbox()
                """);
    }

    private void dropFaultTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS p5_fail_functional_outbox ON outbound_emails");
        jdbc.execute("DROP FUNCTION IF EXISTS p5_fail_functional_outbox()");
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

    private static String body(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
