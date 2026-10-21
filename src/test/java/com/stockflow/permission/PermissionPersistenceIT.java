package com.stockflow.permission;

import com.stockflow.permission.application.GrantedPermission;
import com.stockflow.permission.application.PermissionRequestRepository;
import com.stockflow.permission.application.UserPermissionRepository;
import com.stockflow.permission.domain.PermissionRequest;
import com.stockflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPLIED;
import static com.stockflow.permission.domain.PermissionRequestStatus.APPROVED;
import static com.stockflow.permission.domain.PermissionRequestStatus.PENDING;
import static com.stockflow.permission.domain.PermissionRequestStatus.REJECTED;
import static com.stockflow.shared.persistence.Timestamps.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class PermissionPersistenceIT extends AbstractPostgresIT {

    private static final UUID REQUESTER = UUID.fromString("00000000-0000-0000-0000-00000000a201");
    private static final UUID DECIDER = UUID.fromString("00000000-0000-0000-0000-00000000a202");
    private static final UUID APPLIER = UUID.fromString("00000000-0000-0000-0000-00000000a203");
    private static final UUID OTHER_USER = UUID.fromString("00000000-0000-0000-0000-00000000a204");
    private static final List<UUID> USERS = List.of(REQUESTER, DECIDER, APPLIER, OTHER_USER);
    private static final UUID UNKNOWN_USER = UUID.fromString("00000000-0000-0000-0000-00000000a2ff");

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b201");
    private static final UUID SECOND_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b202");
    private static final UUID UNKNOWN_REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b2ff");
    private static final UUID GRANT_ID = UUID.fromString("00000000-0000-0000-0000-00000000c201");
    private static final UUID SECOND_GRANT_ID = UUID.fromString("00000000-0000-0000-0000-00000000c202");

    // Microsegundos para comprobar que timestamptz no trunca los instantes.
    private static final Instant REQUESTED_AT = Instant.parse("2026-03-01T09:00:00.123456Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-03-01T10:15:30.654321Z");
    private static final Instant APPLIED_AT = Instant.parse("2026-03-01T11:00:00.000001Z");
    private static final Instant BEFORE_REQUEST = Instant.parse("2026-03-01T08:00:00Z");
    // Espacios en los extremos: el motivo se guarda literal, sin trim.
    private static final String REASON = "  No business justification provided  ";

    private static final String INSERT_USER = """
            INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
            VALUES (?, ?, 'hash-placeholder', 'STANDARD', 'PENDING_ACTIVATION', now(), now())
            """;

    private static final String INSERT_REQUEST = """
            INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at,
                                             decided_by, decided_at, rejection_reason, applied_by, applied_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_GRANT = """
            INSERT INTO user_permissions (id, user_id, permission_code, source_request_id, granted_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PermissionRequestRepository requests;

    @Autowired
    private UserPermissionRepository grants;

    /**
     * Otras IT borran {@code users} en su preparación; las FK {@code ON DELETE RESTRICT} lo impedirían si quedaran
     * filas de permisos, por eso se limpian antes y después de cada prueba.
     */
    @BeforeEach
    void prepareUsers() {
        deletePermissionRows();
        for (UUID user : USERS) {
            jdbc.update(INSERT_USER, user, "permission-it-" + user + "@example.test");
        }
    }

    @AfterEach
    void cleanUp() {
        deletePermissionRows();
    }

    private void deletePermissionRows() {
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM permission_requests");
        for (UUID user : USERS) {
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void v3IsAppliedAfterTheUntouchedV1AndV2() {
        // Arrange
        String history = "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank";

        // Act
        List<String> versions = jdbc.queryForList(history, String.class);

        // Assert
        assertThat(versions).containsExactly("1", "2", "3");
        assertThat(jdbc.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version = '3'", String.class))
                .isEqualTo("V3__permission_management.sql");
    }

    @Test
    void v3CreatesPermissionRequestsWithTheExactColumns() {
        // Arrange
        String table = "permission_requests";

        // Act
        List<Map<String, Object>> columns = columnsOf(table);

        // Assert
        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly(
                "id", "requested_by", "permission_code", "status", "requested_at",
                "decided_by", "decided_at", "rejection_reason", "applied_by", "applied_at");
        assertThat(columns).extracting(c -> c.get("data_type")).containsExactly(
                "uuid", "uuid", "character varying", "character varying", "timestamp with time zone",
                "uuid", "timestamp with time zone", "text", "uuid", "timestamp with time zone");
        assertThat(columns).extracting(c -> c.get("is_nullable")).containsExactly(
                "NO", "NO", "NO", "NO", "NO", "YES", "YES", "YES", "YES", "YES");
        assertThat(columns.get(2).get("character_maximum_length")).isEqualTo(64);
        assertThat(columns.get(3).get("character_maximum_length")).isEqualTo(16);
    }

    @Test
    void v3CreatesUserPermissionsWithTheExactColumns() {
        // Arrange
        String table = "user_permissions";

        // Act
        List<Map<String, Object>> columns = columnsOf(table);

        // Assert
        assertThat(columns).extracting(c -> c.get("column_name"))
                .containsExactly("id", "user_id", "permission_code", "source_request_id", "granted_at");
        assertThat(columns).extracting(c -> c.get("data_type")).containsExactly(
                "uuid", "uuid", "character varying", "uuid", "timestamp with time zone");
        assertThat(columns).extracting(c -> c.get("is_nullable")).containsExactly("NO", "NO", "NO", "NO", "NO");
        assertThat(columns.get(2).get("character_maximum_length")).isEqualTo(64);
    }

    @Test
    void v3DeclaresTheForeignKeysWithOnDeleteRestrict() {
        // Arrange
        String foreignKeys = """
                SELECT a.attname || ' -> ' || ref.relname || ' ' || c.confdeltype::text
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_class ref ON ref.oid = c.confrelid
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND t.relname = ?
                ORDER BY a.attname
                """;

        // Act
        List<String> requestKeys = jdbc.queryForList(foreignKeys, String.class, "permission_requests");
        List<String> grantKeys = jdbc.queryForList(foreignKeys, String.class, "user_permissions");

        // Assert ('r' = ON DELETE RESTRICT)
        assertThat(requestKeys).containsExactly(
                "applied_by -> users r", "decided_by -> users r", "requested_by -> users r");
        assertThat(grantKeys).containsExactly(
                "source_request_id -> permission_requests r", "user_id -> users r");
    }

    @Test
    void v3DeclaresTheNamedChecksAndUniques() {
        // Arrange
        String constraints = """
                SELECT c.conname || ' ' || pg_get_constraintdef(c.oid)
                FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                WHERE c.contype = ? AND t.relname = ?
                ORDER BY c.conname
                """;

        // Act
        List<String> requestChecks = jdbc.queryForList(constraints, String.class, "c", "permission_requests");
        List<String> grantChecks = jdbc.queryForList(constraints, String.class, "c", "user_permissions");
        List<String> grantUniques = jdbc.queryForList(constraints, String.class, "u", "user_permissions");
        List<String> requestUniques = jdbc.queryForList(constraints, String.class, "u", "permission_requests");

        // Assert
        assertThat(requestChecks).extracting(def -> def.substring(0, def.indexOf(' '))).containsExactly(
                "ck_permission_requests_metadata",
                "ck_permission_requests_permission_code",
                "ck_permission_requests_status",
                "ck_permission_requests_timeline");
        assertThat(requestChecks.get(1)).contains("'PURCHASE_ORDER_CREATE'");
        assertThat(requestChecks.get(2))
                .contains("'PENDING'", "'APPROVED'", "'APPLIED'", "'REJECTED'")
                .doesNotContain("CANCELLED");
        assertThat(grantChecks).singleElement().asString()
                .startsWith("ck_user_permissions_permission_code ")
                .contains("PURCHASE_ORDER_CREATE");
        assertThat(grantUniques).containsExactly(
                "uq_user_permissions_source_request UNIQUE (source_request_id)",
                "uq_user_permissions_user_permission_code UNIQUE (user_id, permission_code)");
        assertThat(requestUniques).isEmpty();
    }

    @Test
    void v3CreatesOnlyTheContractIndexes() {
        // Arrange
        String indexes = "SELECT indexname || ' ' || indexdef FROM pg_indexes WHERE tablename = ? ORDER BY indexname";

        // Act
        List<String> requestIndexes = jdbc.queryForList(indexes, String.class, "permission_requests");
        List<String> grantIndexes = jdbc.queryForList(indexes, String.class, "user_permissions");

        // Assert
        assertThat(requestIndexes).hasSize(3);
        assertThat(requestIndexes.get(0)).startsWith("ix_permission_requests_requested_by_requested_at ")
                .endsWith("USING btree (requested_by, requested_at)");
        assertThat(requestIndexes.get(1)).startsWith("ix_permission_requests_status_requested_at ")
                .endsWith("USING btree (status, requested_at)");
        assertThat(requestIndexes.get(2)).startsWith("permission_requests_pkey ");
        assertThat(grantIndexes).extracting(def -> def.substring(0, def.indexOf(' '))).containsExactly(
                "uq_user_permissions_source_request",
                "uq_user_permissions_user_permission_code",
                "user_permissions_pkey");
    }

    @Test
    void aPendingRequestRoundTripsThroughRestore() {
        // Arrange
        PermissionRequest pending = newRequest(REQUEST_ID);

        // Act
        requests.save(pending);
        PermissionRequest found = requests.findById(REQUEST_ID).orElseThrow();

        // Assert
        assertRequestIdentity(found, REQUEST_ID);
        assertThat(found.status()).isEqualTo(PENDING);
        assertThat(found.decidedBy()).isEmpty();
        assertThat(found.decidedAt()).isEmpty();
        assertThat(found.rejectionReason()).isEmpty();
        assertThat(found.appliedBy()).isEmpty();
        assertThat(found.appliedAt()).isEmpty();
    }

    @Test
    void anApprovedRequestRoundTripsThroughRestore() {
        // Arrange
        PermissionRequest approved = newRequest(REQUEST_ID);
        approved.approve(DECIDER, DECIDED_AT);

        // Act
        requests.save(approved);
        PermissionRequest found = requests.findById(REQUEST_ID).orElseThrow();

        // Assert
        assertApproved(found);
    }

    @Test
    void aRejectedRequestRoundTripsWithItsLiteralReason() {
        // Arrange
        PermissionRequest rejected = newRequest(REQUEST_ID);
        rejected.reject(DECIDER, DECIDED_AT, REASON);

        // Act
        requests.save(rejected);
        PermissionRequest found = requests.findById(REQUEST_ID).orElseThrow();

        // Assert
        assertRequestIdentity(found, REQUEST_ID);
        assertThat(found.status()).isEqualTo(REJECTED);
        assertThat(found.decidedBy()).contains(DECIDER);
        assertThat(found.decidedAt()).contains(DECIDED_AT);
        assertThat(found.rejectionReason()).contains(REASON);
        assertThat(found.appliedBy()).isEmpty();
        assertThat(found.appliedAt()).isEmpty();
    }

    @Test
    void anAppliedRequestRoundTripsWithItsApplier() {
        // Arrange
        PermissionRequest applied = newAppliedRequest(REQUEST_ID);

        // Act
        requests.save(applied);
        PermissionRequest found = requests.findById(REQUEST_ID).orElseThrow();

        // Assert
        assertApplied(found);
    }

    @Test
    void anUnknownRequestIdIsEmpty() {
        // Arrange
        UUID unknown = UNKNOWN_REQUEST_ID;

        // Act
        Optional<PermissionRequest> found = requests.findById(unknown);

        // Assert
        assertThat(found).isEmpty();
    }

    @Test
    void savingTheSameInstanceAfterEachTransitionUpdatesASingleRow() {
        // Arrange
        PermissionRequest request = newRequest(REQUEST_ID);
        requests.save(request);

        // Act
        request.approve(DECIDER, DECIDED_AT);
        requests.save(request);
        request.apply(APPLIER, APPLIED_AT);
        requests.save(request);

        // Assert
        assertApplied(requests.findById(REQUEST_ID).orElseThrow());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM permission_requests WHERE id = ?", Integer.class, REQUEST_ID)).isOne();
    }

    @Test
    void savingAgainNeverReplacesRequesterPermissionOrRequestDate() {
        // Arrange
        requests.save(newRequest(REQUEST_ID));
        PermissionRequest sameIdOtherOrigin = PermissionRequest.restore(REQUEST_ID, OTHER_USER,
                PURCHASE_ORDER_CREATE, BEFORE_REQUEST, APPROVED, DECIDER, DECIDED_AT, null, null, null);

        // Act
        requests.save(sameIdOtherOrigin);

        // Assert
        assertApproved(requests.findById(REQUEST_ID).orElseThrow());
    }

    @Test
    void aNewConnectionSeesTheSavedRequestAndGrant() throws Exception {
        // Arrange
        requests.save(newAppliedRequest(REQUEST_ID));
        grants.save(new GrantedPermission(GRANT_ID, REQUESTER, PURCHASE_ORDER_CREATE, REQUEST_ID, APPLIED_AT));

        // Act
        try (Connection fresh = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement selectRequest = fresh.prepareStatement("""
                     SELECT requested_by, permission_code, status, requested_at, decided_by, decided_at,
                            rejection_reason, applied_by, applied_at
                     FROM permission_requests WHERE id = ?
                     """);
             PreparedStatement selectGrant = fresh.prepareStatement("""
                     SELECT user_id, permission_code, source_request_id, granted_at
                     FROM user_permissions WHERE id = ?
                     """)) {
            selectRequest.setObject(1, REQUEST_ID);
            selectGrant.setObject(1, GRANT_ID);
            try (ResultSet request = selectRequest.executeQuery(); ResultSet grant = selectGrant.executeQuery()) {

                // Assert
                assertThat(request.next()).isTrue();
                assertThat(request.getObject("requested_by", UUID.class)).isEqualTo(REQUESTER);
                assertThat(request.getString("permission_code")).isEqualTo("PURCHASE_ORDER_CREATE");
                assertThat(request.getString("status")).isEqualTo("APPLIED");
                assertThat(request.getObject("requested_at", OffsetDateTime.class).toInstant())
                        .isEqualTo(REQUESTED_AT);
                assertThat(request.getObject("decided_by", UUID.class)).isEqualTo(DECIDER);
                assertThat(request.getObject("decided_at", OffsetDateTime.class).toInstant())
                        .isEqualTo(DECIDED_AT);
                assertThat(request.getString("rejection_reason")).isNull();
                assertThat(request.getObject("applied_by", UUID.class)).isEqualTo(APPLIER);
                assertThat(request.getObject("applied_at", OffsetDateTime.class).toInstant())
                        .isEqualTo(APPLIED_AT);

                assertThat(grant.next()).isTrue();
                assertThat(grant.getObject("user_id", UUID.class)).isEqualTo(REQUESTER);
                assertThat(grant.getString("permission_code")).isEqualTo("PURCHASE_ORDER_CREATE");
                assertThat(grant.getObject("source_request_id", UUID.class)).isEqualTo(REQUEST_ID);
                assertThat(grant.getObject("granted_at", OffsetDateTime.class).toInstant()).isEqualTo(APPLIED_AT);
            }
        }
    }

    @Test
    void theFourValidRawRowsAreAccepted() {
        // Arrange
        List<RawRequest> valid = List.of(RawRequest.pending(), RawRequest.approved(), RawRequest.rejected(),
                RawRequest.applied());

        // Act
        for (RawRequest row : valid) {
            insertRaw(UUID.randomUUID(), row);
        }

        // Assert
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permission_requests", Integer.class)).isEqualTo(4);
    }

    static Stream<Arguments> invalidRequestRows() {
        return Stream.of(
                Arguments.of("unknown permission code", RawRequest.pending().withCode("INVENTORY_ADMIN"),
                        "ck_permission_requests_permission_code"),
                // Un estado desconocido viola también ck_..._metadata, que PostgreSQL evalúa antes (orden alfabético);
                // la definición de ck_..._status se verifica en v3DeclaresTheNamedChecksAndUniques.
                Arguments.of("unknown status", RawRequest.pending().withStatus("CANCELLED"),
                        "ck_permission_requests_metadata"),
                Arguments.of("lowercase status", RawRequest.pending().withStatus("pending"),
                        "ck_permission_requests_metadata"),
                Arguments.of("null status", RawRequest.pending().withStatus(null), "status"),
                Arguments.of("unknown requester", RawRequest.pending().withRequestedBy(UNKNOWN_USER),
                        "permission_requests_requested_by_fkey"),
                Arguments.of("unknown decider", RawRequest.approved().withDecidedBy(UNKNOWN_USER),
                        "permission_requests_decided_by_fkey"),
                Arguments.of("unknown applier", RawRequest.applied().withAppliedBy(UNKNOWN_USER),
                        "permission_requests_applied_by_fkey"),
                Arguments.of("PENDING with decision", RawRequest.approved().withStatus("PENDING"),
                        "ck_permission_requests_metadata"),
                Arguments.of("PENDING with reason", RawRequest.pending().withReason(REASON),
                        "ck_permission_requests_metadata"),
                Arguments.of("APPROVED without decidedAt", RawRequest.approved().withDecidedAt(null),
                        "ck_permission_requests_metadata"),
                Arguments.of("APPROVED without decidedBy", RawRequest.approved().withDecidedBy(null),
                        "ck_permission_requests_metadata"),
                Arguments.of("APPROVED with reason", RawRequest.approved().withReason(REASON),
                        "ck_permission_requests_metadata"),
                Arguments.of("APPROVED with application", RawRequest.applied().withStatus("APPROVED"),
                        "ck_permission_requests_metadata"),
                Arguments.of("REJECTED with null reason", RawRequest.rejected().withReason(null),
                        "ck_permission_requests_metadata"),
                Arguments.of("REJECTED with empty reason", RawRequest.rejected().withReason(""),
                        "ck_permission_requests_metadata"),
                Arguments.of("REJECTED with blank reason", RawRequest.rejected().withReason(" \t\n "),
                        "ck_permission_requests_metadata"),
                Arguments.of("REJECTED without decision",
                        RawRequest.rejected().withDecidedBy(null).withDecidedAt(null),
                        "ck_permission_requests_metadata"),
                Arguments.of("REJECTED with application",
                        RawRequest.rejected().withAppliedBy(APPLIER).withAppliedAt(APPLIED_AT),
                        "ck_permission_requests_metadata"),
                Arguments.of("APPLIED without appliedBy", RawRequest.applied().withAppliedBy(null),
                        "ck_permission_requests_metadata"),
                Arguments.of("APPLIED without appliedAt", RawRequest.applied().withAppliedAt(null),
                        "ck_permission_requests_metadata"),
                Arguments.of("APPLIED without approval",
                        RawRequest.applied().withDecidedBy(null).withDecidedAt(null),
                        "ck_permission_requests_metadata"),
                Arguments.of("APPLIED with reason", RawRequest.applied().withReason(REASON),
                        "ck_permission_requests_metadata"),
                Arguments.of("decision before request", RawRequest.approved().withDecidedAt(BEFORE_REQUEST),
                        "ck_permission_requests_timeline"),
                Arguments.of("rejection before request", RawRequest.rejected().withDecidedAt(BEFORE_REQUEST),
                        "ck_permission_requests_timeline"),
                Arguments.of("application before approval", RawRequest.applied().withAppliedAt(REQUESTED_AT),
                        "ck_permission_requests_timeline"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequestRows")
    void postgresRejectsAnInvalidRequestRow(String description, RawRequest row, String violated) {
        // Arrange
        UUID id = UUID.randomUUID();

        // Act
        Throwable thrown = catchThrowable(() -> insertRaw(id, row));

        // Assert
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(violated);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permission_requests", Integer.class)).isZero();
    }

    @Test
    void aGrantedPermissionRoundTripsAndIsFoundOnlyForItsUserAndCode() {
        // Arrange
        requests.save(newAppliedRequest(REQUEST_ID));
        GrantedPermission granted =
                new GrantedPermission(GRANT_ID, REQUESTER, PURCHASE_ORDER_CREATE, REQUEST_ID, APPLIED_AT);

        // Act
        grants.save(granted);
        Optional<GrantedPermission> found = grants.findBySourceRequestId(REQUEST_ID);

        // Assert
        assertThat(found).hasValueSatisfying(permission -> {
            assertThat(permission.id()).isEqualTo(GRANT_ID);
            assertThat(permission.userId()).isEqualTo(REQUESTER);
            assertThat(permission.permissionCode()).isEqualTo(PURCHASE_ORDER_CREATE);
            assertThat(permission.sourceRequestId()).isEqualTo(REQUEST_ID);
            assertThat(permission.grantedAt()).isEqualTo(APPLIED_AT);
        });
        assertThat(grants.existsByUserIdAndPermissionCode(REQUESTER, PURCHASE_ORDER_CREATE)).isTrue();
        assertThat(grants.existsByUserIdAndPermissionCode(OTHER_USER, PURCHASE_ORDER_CREATE)).isFalse();
        assertThat(grants.findBySourceRequestId(UNKNOWN_REQUEST_ID)).isEmpty();
    }

    @Test
    void aSecondGrantFromTheSameSourceRequestIsRejected() {
        // Arrange
        requests.save(newAppliedRequest(REQUEST_ID));
        grants.save(new GrantedPermission(GRANT_ID, REQUESTER, PURCHASE_ORDER_CREATE, REQUEST_ID, APPLIED_AT));
        GrantedPermission duplicate =
                new GrantedPermission(SECOND_GRANT_ID, OTHER_USER, PURCHASE_ORDER_CREATE, REQUEST_ID, APPLIED_AT);

        // Act
        Throwable thrown = catchThrowable(() -> grants.save(duplicate));

        // Assert
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_user_permissions_source_request");
        assertThat(grantCount()).isOne();
        assertThat(grants.existsByUserIdAndPermissionCode(OTHER_USER, PURCHASE_ORDER_CREATE)).isFalse();
    }

    @Test
    void aSecondGrantOfTheSamePermissionToTheSameUserIsRejected() {
        // Arrange
        requests.save(newAppliedRequest(REQUEST_ID));
        requests.save(newAppliedRequest(SECOND_REQUEST_ID));
        grants.save(new GrantedPermission(GRANT_ID, REQUESTER, PURCHASE_ORDER_CREATE, REQUEST_ID, APPLIED_AT));
        GrantedPermission duplicate = new GrantedPermission(SECOND_GRANT_ID, REQUESTER, PURCHASE_ORDER_CREATE,
                SECOND_REQUEST_ID, APPLIED_AT);

        // Act
        Throwable thrown = catchThrowable(() -> grants.save(duplicate));

        // Assert
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_user_permissions_user_permission_code");
        assertThat(grantCount()).isOne();
        assertThat(grants.findBySourceRequestId(SECOND_REQUEST_ID)).isEmpty();
    }

    @Test
    void aGrantWithUnknownReferencesOrCodeIsRejected() {
        // Arrange
        requests.save(newAppliedRequest(REQUEST_ID));

        // Act
        Throwable unknownUser = catchThrowable(() -> grants.save(
                new GrantedPermission(GRANT_ID, UNKNOWN_USER, PURCHASE_ORDER_CREATE, REQUEST_ID, APPLIED_AT)));
        Throwable unknownSource = catchThrowable(() -> grants.save(
                new GrantedPermission(GRANT_ID, REQUESTER, PURCHASE_ORDER_CREATE, UNKNOWN_REQUEST_ID, APPLIED_AT)));
        Throwable unknownCode = catchThrowable(() -> jdbc.update(INSERT_GRANT,
                GRANT_ID, REQUESTER, "INVENTORY_ADMIN", REQUEST_ID, utc(APPLIED_AT)));

        // Assert
        assertThat(unknownUser).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_permissions_user_id_fkey");
        assertThat(unknownSource).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("user_permissions_source_request_id_fkey");
        assertThat(unknownCode).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_user_permissions_permission_code");
        assertThat(grantCount()).isZero();
    }

    private static PermissionRequest newRequest(UUID id) {
        return PermissionRequest.create(id, REQUESTER, PURCHASE_ORDER_CREATE, REQUESTED_AT);
    }

    private static PermissionRequest newAppliedRequest(UUID id) {
        PermissionRequest request = newRequest(id);
        request.approve(DECIDER, DECIDED_AT);
        request.apply(APPLIER, APPLIED_AT);
        return request;
    }

    private static void assertRequestIdentity(PermissionRequest found, UUID id) {
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.requestedBy()).isEqualTo(REQUESTER);
        assertThat(found.permissionCode()).isEqualTo(PURCHASE_ORDER_CREATE);
        assertThat(found.requestedAt()).isEqualTo(REQUESTED_AT);
    }

    private static void assertApproved(PermissionRequest found) {
        assertRequestIdentity(found, REQUEST_ID);
        assertThat(found.status()).isEqualTo(APPROVED);
        assertThat(found.decidedBy()).contains(DECIDER);
        assertThat(found.decidedAt()).contains(DECIDED_AT);
        assertThat(found.rejectionReason()).isEmpty();
        assertThat(found.appliedBy()).isEmpty();
        assertThat(found.appliedAt()).isEmpty();
    }

    private static void assertApplied(PermissionRequest found) {
        assertRequestIdentity(found, REQUEST_ID);
        assertThat(found.status()).isEqualTo(APPLIED);
        assertThat(found.decidedBy()).contains(DECIDER);
        assertThat(found.decidedAt()).contains(DECIDED_AT);
        assertThat(found.rejectionReason()).isEmpty();
        assertThat(found.appliedBy()).contains(APPLIER);
        assertThat(found.appliedAt()).contains(APPLIED_AT);
    }

    private List<Map<String, Object>> columnsOf(String table) {
        return jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """, table);
    }

    private int grantCount() {
        return jdbc.queryForObject("SELECT count(*) FROM user_permissions", Integer.class);
    }

    private void insertRaw(UUID id, RawRequest row) {
        jdbc.update(INSERT_REQUEST, id, row.requestedBy(), row.code(), row.status(), utc(REQUESTED_AT),
                row.decidedBy(), nullableUtc(row.decidedAt()), row.reason(), row.appliedBy(),
                nullableUtc(row.appliedAt()));
    }

    private static OffsetDateTime nullableUtc(Instant instant) {
        return instant == null ? null : utc(instant);
    }

    /** Fila escrita con SQL crudo, sin pasar por el dominio, para ejercitar solo las restricciones de PostgreSQL. */
    record RawRequest(String code, String status, UUID requestedBy, UUID decidedBy, Instant decidedAt,
                      String reason, UUID appliedBy, Instant appliedAt) {

        static RawRequest pending() {
            return new RawRequest("PURCHASE_ORDER_CREATE", "PENDING", REQUESTER, null, null, null, null, null);
        }

        static RawRequest approved() {
            return new RawRequest("PURCHASE_ORDER_CREATE", "APPROVED", REQUESTER, DECIDER, DECIDED_AT,
                    null, null, null);
        }

        static RawRequest rejected() {
            return new RawRequest("PURCHASE_ORDER_CREATE", "REJECTED", REQUESTER, DECIDER, DECIDED_AT,
                    REASON, null, null);
        }

        static RawRequest applied() {
            return new RawRequest("PURCHASE_ORDER_CREATE", "APPLIED", REQUESTER, DECIDER, DECIDED_AT,
                    null, APPLIER, APPLIED_AT);
        }

        RawRequest withCode(String value) {
            return new RawRequest(value, status, requestedBy, decidedBy, decidedAt, reason, appliedBy, appliedAt);
        }

        RawRequest withStatus(String value) {
            return new RawRequest(code, value, requestedBy, decidedBy, decidedAt, reason, appliedBy, appliedAt);
        }

        RawRequest withRequestedBy(UUID value) {
            return new RawRequest(code, status, value, decidedBy, decidedAt, reason, appliedBy, appliedAt);
        }

        RawRequest withDecidedBy(UUID value) {
            return new RawRequest(code, status, requestedBy, value, decidedAt, reason, appliedBy, appliedAt);
        }

        RawRequest withDecidedAt(Instant value) {
            return new RawRequest(code, status, requestedBy, decidedBy, value, reason, appliedBy, appliedAt);
        }

        RawRequest withReason(String value) {
            return new RawRequest(code, status, requestedBy, decidedBy, decidedAt, value, appliedBy, appliedAt);
        }

        RawRequest withAppliedBy(UUID value) {
            return new RawRequest(code, status, requestedBy, decidedBy, decidedAt, reason, value, appliedAt);
        }

        RawRequest withAppliedAt(Instant value) {
            return new RawRequest(code, status, requestedBy, decidedBy, decidedAt, reason, appliedBy, value);
        }
    }
}
