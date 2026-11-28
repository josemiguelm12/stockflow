package com.stockflow.inventory;

import com.stockflow.inventory.application.PurchaseOrderRepository;
import com.stockflow.inventory.domain.PurchaseOrder;
import com.stockflow.inventory.domain.PurchaseOrderStatus;
import com.stockflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseOrderPersistenceIT extends AbstractPostgresIT {

    private static final String INSERT_ORDER =
            "INSERT INTO purchase_orders (id, supplier_id, state, created_at) VALUES (?, NULL, ?, now())";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PurchaseOrderRepository orders;

    @Test
    void v2CreatesThePurchaseOrdersTableAndV5AddsOnlyOwnerAndTransitionDates() {
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class)).isTrue();

        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'purchase_orders'
                ORDER BY ordinal_position
                """);
        assertThat(columns).extracting(c -> c.get("column_name"))
                .containsExactly("id", "supplier_id", "state", "created_at", "owner_user_id", "submitted_at",
                        "approved_at");
        assertThat(columns).extracting(c -> c.get("data_type"))
                .containsExactly("uuid", "uuid", "character varying", "timestamp with time zone", "uuid",
                        "timestamp with time zone", "timestamp with time zone");
        assertThat(columns).extracting(c -> c.get("is_nullable"))
                .containsExactly("NO", "YES", "NO", "NO", "YES", "YES", "YES");
        assertThat(columns.get(2).get("character_maximum_length")).isEqualTo(16);

        // V2 no tenía claves foráneas; V5 añade únicamente la del propietario (users).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 'purchase_orders' AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 'purchase_orders' AND constraint_type = 'FOREIGN KEY'
                  AND constraint_name = 'purchase_orders_owner_user_id_fkey'
                """, Integer.class)).isOne();
    }

    @Test
    void theCheckConstraintAcceptsOnlyTheFiveStates() {
        for (PurchaseOrderStatus status : PurchaseOrderStatus.values()) {
            jdbc.update(INSERT_ORDER, UUID.randomUUID(), status.name());
        }

        for (String invalid : List.of("INVALID", "draft", "", "PENDING")) {
            assertThatThrownBy(() -> jdbc.update(INSERT_ORDER, UUID.randomUUID(), invalid))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_purchase_orders_state");
        }
        assertThatThrownBy(() -> jdbc.update(INSERT_ORDER, UUID.randomUUID(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anOrderWithoutSupplierIsSavedAndReadBack() {
        Instant createdAt = Instant.parse("2026-03-04T05:06:07.123456Z");
        UUID owner = insertUser();
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), null, owner, createdAt);

        orders.save(order);

        PurchaseOrder found = orders.findById(order.id()).orElseThrow();
        assertThat(found.id()).isEqualTo(order.id());
        assertThat(found.supplierId()).isEmpty();
        assertThat(found.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(found.createdAt()).isEqualTo(createdAt);
        assertThat(found.ownerId()).contains(owner);
        assertThat(found.submittedAt()).isEmpty();
        assertThat(found.approvedAt()).isEmpty();
    }

    @Test
    void anOrderWithSupplierKeepsItsStateAndDateAfterTransitions() throws Exception {
        UUID supplierId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-04T05:06:07.654321Z");
        UUID owner = insertUser();
        Instant submittedAt = Instant.parse("2026-03-05T00:00:00.111111Z");
        Instant approvedAt = Instant.parse("2026-03-06T00:00:00.222222Z");
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), supplierId, owner, createdAt);
        orders.save(order);

        order.submit(submittedAt);
        order.approve(approvedAt);
        orders.save(order);

        PurchaseOrder found = orders.findById(order.id()).orElseThrow();
        assertThat(found.supplierId()).contains(supplierId);
        assertThat(found.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(found.createdAt()).isEqualTo(createdAt);
        assertThat(found.ownerId()).contains(owner);
        assertThat(found.submittedAt()).contains(submittedAt);
        assertThat(found.approvedAt()).contains(approvedAt);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM purchase_orders WHERE id = ?", Integer.class, order.id())).isOne();

        // Una conexión nueva ve lo mismo: el estado está persistido, no en caché.
        try (Connection fresh = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = fresh.prepareStatement(
                     "SELECT supplier_id, state, created_at FROM purchase_orders WHERE id = ?")) {
            select.setObject(1, order.id());
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("supplier_id", UUID.class)).isEqualTo(supplierId);
                assertThat(rs.getString("state")).isEqualTo("APPROVED");
                assertThat(rs.getObject("created_at", OffsetDateTime.class).toInstant()).isEqualTo(createdAt);
            }
        }
    }

    @Test
    void savingAgainDoesNotChangeSupplierOrCreationDate() {
        UUID id = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-04T05:06:07Z");
        UUID owner = insertUser();
        orders.save(PurchaseOrder.create(id, supplierId, owner, createdAt));

        orders.save(PurchaseOrder.restore(id, null, null, PurchaseOrderStatus.CANCELLED,
                Instant.parse("2030-01-01T00:00:00Z"), null, null));

        PurchaseOrder found = orders.findById(id).orElseThrow();
        assertThat(found.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(found.supplierId()).contains(supplierId);
        assertThat(found.createdAt()).isEqualTo(createdAt);
        assertThat(found.ownerId()).as("the owner is never rewritten").contains(owner);
    }

    @Test
    void anUnknownIdIsEmpty() {
        assertThat(orders.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aHistoricalRowWithoutOwnerNorDatesIsReadBackWithoutInventingThem() {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_ORDER, id, "SUBMITTED");

        PurchaseOrder found = orders.findById(id).orElseThrow();

        assertThat(found.ownerId()).isEmpty();
        assertThat(found.submittedAt()).isEmpty();
        assertThat(found.approvedAt()).isEmpty();
        assertThat(found.status()).isEqualTo(PurchaseOrderStatus.SUBMITTED);
    }

    @Test
    void approvingAHistoricalRowRecordsOnlyApprovedAtAndPassesTheTimelineCheck() {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_ORDER, id, "SUBMITTED");
        PurchaseOrder historical = orders.lockById(id).orElseThrow();

        historical.approve(Instant.parse("2026-04-01T00:00:00Z"));
        orders.save(historical);

        PurchaseOrder found = orders.findById(id).orElseThrow();
        assertThat(found.ownerId()).isEmpty();
        assertThat(found.submittedAt()).isEmpty();
        assertThat(found.approvedAt()).contains(Instant.parse("2026-04-01T00:00:00Z"));
    }

    @Test
    void theTimelineConstraintRejectsApprovalBeforeSubmission() {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_ORDER, id, "APPROVED");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE purchase_orders SET submitted_at = now(), approved_at = now() - interval '1 hour'"
                        + " WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_purchase_orders_approval_timeline");
    }

    @Test
    void theOwnerMustBeAnExistingUser() {
        assertThatThrownBy(() -> orders.save(PurchaseOrder.create(UUID.randomUUID(), null, UUID.randomUUID(),
                Instant.parse("2026-03-04T05:06:07Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                  password_reset_required, failed_login_attempts, locked_until, created_at, updated_at)
                VALUES (?, ?, 'hash', 'STANDARD', 'ACTIVE', now(), false, 0, NULL, now(), now())
                """, id, id + "@example.test");
        return id;
    }
}
