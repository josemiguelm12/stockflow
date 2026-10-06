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
    void v2CreatesThePurchaseOrdersTableWithTheExactColumns() {
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class)).isTrue();

        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'purchase_orders'
                ORDER BY ordinal_position
                """);
        assertThat(columns).extracting(c -> c.get("column_name"))
                .containsExactly("id", "supplier_id", "state", "created_at");
        assertThat(columns).extracting(c -> c.get("data_type"))
                .containsExactly("uuid", "uuid", "character varying", "timestamp with time zone");
        assertThat(columns).extracting(c -> c.get("is_nullable"))
                .containsExactly("NO", "YES", "NO", "NO");
        assertThat(columns.get(2).get("character_maximum_length")).isEqualTo(16);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.table_constraints
                WHERE table_name = 'purchase_orders' AND constraint_type = 'FOREIGN KEY'
                """, Integer.class)).isZero();
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
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), null, createdAt);

        orders.save(order);

        PurchaseOrder found = orders.findById(order.id()).orElseThrow();
        assertThat(found.id()).isEqualTo(order.id());
        assertThat(found.supplierId()).isEmpty();
        assertThat(found.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(found.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void anOrderWithSupplierKeepsItsStateAndDateAfterTransitions() throws Exception {
        UUID supplierId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-03-04T05:06:07.654321Z");
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), supplierId, createdAt);
        orders.save(order);

        order.transitionTo(PurchaseOrderStatus.SUBMITTED);
        order.transitionTo(PurchaseOrderStatus.APPROVED);
        orders.save(order);

        PurchaseOrder found = orders.findById(order.id()).orElseThrow();
        assertThat(found.supplierId()).contains(supplierId);
        assertThat(found.status()).isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(found.createdAt()).isEqualTo(createdAt);
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
        orders.save(PurchaseOrder.create(id, supplierId, createdAt));

        orders.save(PurchaseOrder.restore(id, null, PurchaseOrderStatus.CANCELLED,
                Instant.parse("2030-01-01T00:00:00Z")));

        PurchaseOrder found = orders.findById(id).orElseThrow();
        assertThat(found.status()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(found.supplierId()).contains(supplierId);
        assertThat(found.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void anUnknownIdIsEmpty() {
        assertThat(orders.findById(UUID.randomUUID())).isEmpty();
    }
}
