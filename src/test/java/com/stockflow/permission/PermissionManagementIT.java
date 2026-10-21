package com.stockflow.permission;

import com.stockflow.inventory.application.CreatePurchaseOrder;
import com.stockflow.inventory.application.PurchaseOrderPermissionDeniedException;
import com.stockflow.permission.application.ApprovePermissionRequest;
import com.stockflow.permission.application.CreatePermissionRequest;
import com.stockflow.permission.application.ListPendingPermissionRequests;
import com.stockflow.permission.application.PermissionActorNotAuthorizedException;
import com.stockflow.permission.application.PermissionChecker;
import com.stockflow.permission.application.PermissionRequestNotFoundException;
import com.stockflow.permission.application.PermissionSelfResolutionException;
import com.stockflow.permission.application.RejectPermissionRequest;
import com.stockflow.permission.domain.InvalidPermissionRequestException;
import com.stockflow.permission.domain.InvalidPermissionRequestTransitionException;
import com.stockflow.support.AbstractPostgresIT;
import com.stockflow.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;
import static com.stockflow.shared.persistence.Timestamps.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A2-T03 contra PostgreSQL real: casos de uso, autorización con el rol persistido, atomicidad de aprobar + conceder +
 * aplicar (RD-09) y la orden de compra protegida por la concesión (RF-GP-05).
 */
class PermissionManagementIT extends AbstractPostgresIT {

    private static final UUID STANDARD_USER = UUID.fromString("00000000-0000-0000-0000-00000000a501");
    private static final UUID OTHER_STANDARD = UUID.fromString("00000000-0000-0000-0000-00000000a502");
    private static final UUID ADMIN_USER = UUID.fromString("00000000-0000-0000-0000-00000000a503");
    private static final UUID OTHER_ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a504");
    private static final List<UUID> USERS = List.of(STANDARD_USER, OTHER_STANDARD, ADMIN_USER, OTHER_ADMIN);

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b501");
    private static final UUID SECOND_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b502");
    private static final UUID THIRD_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b503");
    private static final UUID FOURTH_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b504");
    private static final UUID EXISTING_GRANT_ID = UUID.fromString("00000000-0000-0000-0000-00000000c501");
    private static final UUID SUPPLIER = UUID.fromString("00000000-0000-0000-0000-00000000d501");

    // Anteriores al reloj de prueba, que empieza en 2026-01-01 y solo avanza.
    private static final Instant EARLY = Instant.parse("2025-12-01T09:00:00.123456Z");
    private static final Instant LATE = Instant.parse("2025-12-02T09:00:00Z");
    private static final Instant DECIDED = Instant.parse("2025-12-03T09:00:00Z");
    private static final String REASON = "  Not needed for the current role  ";

    private static final String INSERT_USER = """
            INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
            VALUES (?, ?, 'hash-placeholder', ?, 'ACTIVE', now(), now())
            """;

    private static final String INSERT_PENDING = """
            INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at)
            VALUES (?, ?, 'PURCHASE_ORDER_CREATE', 'PENDING', ?)
            """;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private CreatePermissionRequest create;
    @Autowired
    private ListPendingPermissionRequests list;
    @Autowired
    private ApprovePermissionRequest approve;
    @Autowired
    private RejectPermissionRequest reject;
    @Autowired
    private PermissionChecker checker;
    @Autowired
    private CreatePurchaseOrder createOrder;

    private final List<UUID> createdOrders = new ArrayList<>();

    /** Otras IT borran {@code users}; las FK RESTRICT exigen limpiar los permisos antes y después. */
    @BeforeEach
    void prepareUsers() {
        deleteRows();
        jdbc.update(INSERT_USER, STANDARD_USER, "perm-mgmt-standard@example.test", "STANDARD");
        jdbc.update(INSERT_USER, OTHER_STANDARD, "perm-mgmt-other-standard@example.test", "STANDARD");
        jdbc.update(INSERT_USER, ADMIN_USER, "perm-mgmt-admin@example.test", "ADMIN");
        jdbc.update(INSERT_USER, OTHER_ADMIN, "perm-mgmt-other-admin@example.test", "ADMIN");
    }

    @AfterEach
    void cleanUp() {
        deleteRows();
    }

    private void deleteRows() {
        for (UUID order : createdOrders) {
            jdbc.update("DELETE FROM purchase_orders WHERE id = ?", order);
        }
        createdOrders.clear();
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM permission_requests");
        for (UUID user : USERS) {
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    // ---- crear y listar ----

    @Test
    void aStandardCreatesAPendingRowWhoseIdentityAndInstantComeFromTheServer() {
        // Arrange
        Instant now = clock.instant();

        // Act
        CreatePermissionRequest.Result result =
                create.create(STANDARD_USER, new CreatePermissionRequest.Command(PURCHASE_ORDER_CREATE));

        // Assert
        Map<String, Object> row = requestRow(result.id());
        assertThat(row.get("requested_by")).isEqualTo(STANDARD_USER);
        assertThat(row.get("permission_code")).isEqualTo("PURCHASE_ORDER_CREATE");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(instant(row, "requested_at")).isEqualTo(now);
        assertThat(row.get("decided_by")).isNull();
        assertThat(row.get("applied_by")).isNull();
    }

    @Test
    void listingsReturnOnlyPendingInRequestOrderAndScopeStandardsToTheirOwn() {
        // Arrange — mismo instante para SECOND y THIRD: desempata el id.
        jdbc.update(INSERT_PENDING, THIRD_REQUEST_ID, OTHER_STANDARD, utc(LATE));
        jdbc.update(INSERT_PENDING, SECOND_REQUEST_ID, STANDARD_USER, utc(LATE));
        jdbc.update(INSERT_PENDING, REQUEST_ID, OTHER_STANDARD, utc(EARLY));
        insertApplied(FOURTH_REQUEST_ID, STANDARD_USER);

        // Act
        List<ListPendingPermissionRequests.Item> global = list.list(ADMIN_USER);
        List<ListPendingPermissionRequests.Item> own = list.list(STANDARD_USER);

        // Assert
        assertThat(global).extracting(ListPendingPermissionRequests.Item::id)
                .containsExactly(REQUEST_ID, SECOND_REQUEST_ID, THIRD_REQUEST_ID);
        assertThat(global.get(0).requestedAt()).isEqualTo(EARLY);
        assertThat(own).containsExactly(new ListPendingPermissionRequests.Item(
                SECOND_REQUEST_ID, STANDARD_USER, PURCHASE_ORDER_CREATE, LATE));
    }

    // ---- aprobar: camino verde atómico ----

    @Test
    void approvalPersistsAppliedRequestAndExactlyOneGrantThatUnlocksPurchaseOrders() {
        // Arrange
        jdbc.update(INSERT_PENDING, REQUEST_ID, STANDARD_USER, utc(EARLY));
        Instant now = clock.instant();

        // Act
        approve.approve(ADMIN_USER, REQUEST_ID);

        // Assert
        Map<String, Object> row = requestRow(REQUEST_ID);
        assertThat(row.get("status")).isEqualTo("APPLIED");
        assertThat(row.get("decided_by")).isEqualTo(ADMIN_USER);
        assertThat(instant(row, "decided_at")).isEqualTo(now);
        assertThat(row.get("applied_by")).isEqualTo(ADMIN_USER);
        assertThat(instant(row, "applied_at")).isEqualTo(now);
        assertThat(row.get("rejection_reason")).isNull();
        List<Map<String, Object>> grants = jdbc.queryForList("SELECT * FROM user_permissions");
        assertThat(grants).singleElement().satisfies(grant -> {
            assertThat(grant.get("user_id")).isEqualTo(STANDARD_USER);
            assertThat(grant.get("permission_code")).isEqualTo("PURCHASE_ORDER_CREATE");
            assertThat(grant.get("source_request_id")).isEqualTo(REQUEST_ID);
            assertThat(instant(grant, "granted_at")).isEqualTo(now);
        });
        assertThat(checker.hasPermission(STANDARD_USER, PURCHASE_ORDER_CREATE)).isTrue();
        UUID order = createOrder.create(STANDARD_USER, SUPPLIER);
        createdOrders.add(order);
        assertThat(jdbc.queryForObject("SELECT state FROM purchase_orders WHERE id = ?", String.class, order))
                .isEqualTo("DRAFT");
    }

    @Test
    void aFailingGrantInsertRollsBackTheWholeApproval() {
        // Arrange — el usuario ya tiene el permiso por otra solicitud: el INSERT viola (user_id, permission_code).
        insertApplied(SECOND_REQUEST_ID, STANDARD_USER);
        jdbc.update("""
                INSERT INTO user_permissions (id, user_id, permission_code, source_request_id, granted_at)
                VALUES (?, ?, 'PURCHASE_ORDER_CREATE', ?, ?)
                """, EXISTING_GRANT_ID, STANDARD_USER, SECOND_REQUEST_ID, utc(DECIDED));
        jdbc.update(INSERT_PENDING, REQUEST_ID, STANDARD_USER, utc(EARLY));
        Map<String, Object> before = requestRow(REQUEST_ID);

        // Act
        Throwable thrown = catchThrowable(() -> approve.approve(ADMIN_USER, REQUEST_ID));

        // Assert
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(requestRow(REQUEST_ID)).isEqualTo(before);
        assertThat(requestRow(REQUEST_ID).get("status")).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM user_permissions WHERE source_request_id = ?", REQUEST_ID)).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM user_permissions", UUID.class))
                .containsExactly(EXISTING_GRANT_ID);
    }

    @Test
    void aSecondApprovalOrALateRejectionChangesNoRowAndAddsNoGrant() {
        // Arrange
        jdbc.update(INSERT_PENDING, REQUEST_ID, STANDARD_USER, utc(EARLY));
        approve.approve(ADMIN_USER, REQUEST_ID);
        Map<String, Object> before = requestRow(REQUEST_ID);

        // Act
        Throwable secondApproval = catchThrowable(() -> approve.approve(OTHER_ADMIN, REQUEST_ID));
        Throwable lateRejection = catchThrowable(() -> reject.reject(OTHER_ADMIN, REQUEST_ID, REASON));

        // Assert
        assertThat(secondApproval).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertThat(lateRejection).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertThat(requestRow(REQUEST_ID)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM user_permissions")).isEqualTo(1);
    }

    // ---- rechazar ----

    @Test
    void aRejectionPersistsTheLiteralReasonAndNoGrant() {
        // Arrange
        jdbc.update(INSERT_PENDING, REQUEST_ID, STANDARD_USER, utc(EARLY));
        Instant now = clock.instant();

        // Act
        reject.reject(ADMIN_USER, REQUEST_ID, REASON);

        // Assert
        Map<String, Object> row = requestRow(REQUEST_ID);
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("rejection_reason")).isEqualTo(REASON);
        assertThat(row.get("decided_by")).isEqualTo(ADMIN_USER);
        assertThat(instant(row, "decided_at")).isEqualTo(now);
        assertThat(row.get("applied_by")).isNull();
        assertThat(count("SELECT count(*) FROM user_permissions")).isZero();
        Throwable approval = catchThrowable(() -> approve.approve(OTHER_ADMIN, REQUEST_ID));
        assertThat(approval).isInstanceOf(InvalidPermissionRequestTransitionException.class);
        assertThat(requestRow(REQUEST_ID)).isEqualTo(row);
    }

    @Test
    void aRejectionWithoutAWrittenReasonLeavesTheRowUntouched() {
        // Arrange
        jdbc.update(INSERT_PENDING, REQUEST_ID, STANDARD_USER, utc(EARLY));
        Map<String, Object> before = requestRow(REQUEST_ID);

        // Act
        List<Throwable> failures = new ArrayList<>();
        for (String reason : new String[]{null, "", "   "}) {
            failures.add(catchThrowable(() -> reject.reject(ADMIN_USER, REQUEST_ID, reason)));
        }

        // Assert
        assertThat(failures).allSatisfy(f -> assertThat(f).isInstanceOf(InvalidPermissionRequestException.class));
        assertThat(requestRow(REQUEST_ID)).isEqualTo(before);
    }

    // ---- RD-06: actor y auto-resolución ----

    @Test
    void anAdminCannotResolveItsOwnRequest() {
        // Arrange
        jdbc.update(INSERT_PENDING, REQUEST_ID, ADMIN_USER, utc(EARLY));
        Map<String, Object> before = requestRow(REQUEST_ID);

        // Act
        Throwable approval = catchThrowable(() -> approve.approve(ADMIN_USER, REQUEST_ID));
        Throwable rejection = catchThrowable(() -> reject.reject(ADMIN_USER, REQUEST_ID, REASON));

        // Assert
        assertThat(approval).isInstanceOf(PermissionSelfResolutionException.class);
        assertThat(rejection).isInstanceOf(PermissionSelfResolutionException.class);
        assertThat(requestRow(REQUEST_ID)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM user_permissions")).isZero();
    }

    @Test
    void resolutionAndGlobalListingFollowThePersistedRoleAndStatus() {
        // Arrange
        jdbc.update(INSERT_PENDING, REQUEST_ID, STANDARD_USER, utc(EARLY));
        jdbc.update(INSERT_PENDING, SECOND_REQUEST_ID, OTHER_STANDARD, utc(LATE));
        jdbc.update("UPDATE users SET role = 'STANDARD' WHERE id = ?", ADMIN_USER);
        jdbc.update("UPDATE users SET account_status = 'DISABLED' WHERE id = ?", OTHER_ADMIN);
        Map<String, Object> before = requestRow(REQUEST_ID);

        // Act
        List<Throwable> failures = new ArrayList<>();
        for (UUID actor : List.of(ADMIN_USER, OTHER_ADMIN, OTHER_STANDARD)) {
            failures.add(catchThrowable(() -> approve.approve(actor, REQUEST_ID)));
            failures.add(catchThrowable(() -> reject.reject(actor, REQUEST_ID, REASON)));
        }
        Throwable disabledListing = catchThrowable(() -> list.list(OTHER_ADMIN));
        List<ListPendingPermissionRequests.Item> demotedListing = list.list(ADMIN_USER);

        // Assert
        assertThat(failures).hasSize(6)
                .allSatisfy(f -> assertThat(f).isInstanceOf(PermissionActorNotAuthorizedException.class));
        assertThat(disabledListing).isInstanceOf(PermissionActorNotAuthorizedException.class);
        assertThat(demotedListing).isEmpty();
        assertThat(requestRow(REQUEST_ID)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM user_permissions")).isZero();
    }

    @Test
    void anUnknownRequestIsReportedAsNotFound() {
        // Arrange
        UUID unknown = UUID.fromString("00000000-0000-0000-0000-00000000b5ff");

        // Act
        Throwable approval = catchThrowable(() -> approve.approve(ADMIN_USER, unknown));
        Throwable rejection = catchThrowable(() -> reject.reject(ADMIN_USER, unknown, REASON));

        // Assert
        assertThat(approval).isInstanceOf(PermissionRequestNotFoundException.class);
        assertThat(rejection).isInstanceOf(PermissionRequestNotFoundException.class);
    }

    // ---- RF-GP-05: orden de compra protegida ----

    @Test
    void purchaseOrdersRequireThePersistedGrantWhateverTheRole() {
        // Arrange
        long ordersBefore = count("SELECT count(*) FROM purchase_orders");

        // Act
        Throwable standard = catchThrowable(() -> createOrder.create(STANDARD_USER, SUPPLIER));
        Throwable admin = catchThrowable(() -> createOrder.create(ADMIN_USER, SUPPLIER));

        // Assert
        assertThat(standard).isInstanceOf(PurchaseOrderPermissionDeniedException.class);
        assertThat(admin).isInstanceOf(PurchaseOrderPermissionDeniedException.class);
        assertThat(count("SELECT count(*) FROM purchase_orders")).isEqualTo(ordersBefore);
    }

    // ---- utilidades ----

    private void insertApplied(UUID id, UUID requester) {
        jdbc.update("""
                INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at,
                                                 decided_by, decided_at, applied_by, applied_at)
                VALUES (?, ?, 'PURCHASE_ORDER_CREATE', 'APPLIED', ?, ?, ?, ?, ?)
                """, id, requester, utc(EARLY), OTHER_ADMIN, utc(DECIDED), OTHER_ADMIN, utc(DECIDED));
    }

    private Map<String, Object> requestRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM permission_requests WHERE id = ?", id);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static Instant instant(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return ((Timestamp) value).toInstant();
    }
}
