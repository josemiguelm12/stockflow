package com.stockflow.inventory;

import com.stockflow.inventory.application.CreatePurchaseOrder;
import com.stockflow.inventory.application.PurchaseOrderActorNotAuthorizedException;
import com.stockflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P3-F1 (QA V1): {@link CreatePurchaseOrder} invocado directamente, sin HTTP ni SecurityConfig, contra PostgreSQL
 * real. Una concesión {@code PURCHASE_ORDER_CREATE} persistida no basta si la cuenta ya no está {@code ACTIVE}.
 */
class CreatePurchaseOrderAuthorizationIT extends AbstractPostgresIT {

    private static final UUID STANDARD = UUID.fromString("00000000-0000-0000-0000-00000000c701");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000c702");
    private static final List<UUID> USERS = List.of(STANDARD, ADMIN);
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-00000000c711");
    private static final UUID GRANT = UUID.fromString("00000000-0000-0000-0000-00000000c721");
    private static final UUID SUPPLIER = UUID.fromString("00000000-0000-0000-0000-00000000c731");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(Instant.parse("2026-03-01T10:00:00Z"),
            ZoneOffset.UTC);

    private static final String INSERT_USER = """
            INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
            VALUES (?, ?, 'hash-placeholder', ?, 'ACTIVE', now(), now())
            """;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CreatePurchaseOrder createOrder;

    private final List<UUID> createdOrders = new ArrayList<>();

    /** El STANDARD tiene la concesión aplicada por el ADMIN, como tras RF-GP-03. */
    @BeforeEach
    void prepare() {
        deleteRows();
        jdbc.update(INSERT_USER, STANDARD, "po-create-standard@example.test", "STANDARD");
        jdbc.update(INSERT_USER, ADMIN, "po-create-admin@example.test", "ADMIN");
        jdbc.update("""
                INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at,
                                                 decided_by, decided_at, applied_by, applied_at)
                VALUES (?, ?, 'PURCHASE_ORDER_CREATE', 'APPLIED', ?, ?, ?, ?, ?)
                """, REQUEST, STANDARD, AT, ADMIN, AT, ADMIN, AT);
        jdbc.update("""
                INSERT INTO user_permissions (id, user_id, permission_code, source_request_id, granted_at)
                VALUES (?, ?, 'PURCHASE_ORDER_CREATE', ?, ?)
                """, GRANT, STANDARD, REQUEST, AT);
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
        jdbc.update("DELETE FROM user_permissions WHERE id = ?", GRANT);
        jdbc.update("DELETE FROM permission_requests WHERE id = ?", REQUEST);
        for (UUID user : USERS) {
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void anActiveStandardWithThePersistedGrantCreatesADraftByDirectInvocation() {
        // Arrange
        // (STANDARD está ACTIVE y tiene la concesión)

        // Act
        UUID order = createOrder.create(STANDARD, SUPPLIER);
        createdOrders.add(order);

        // Assert
        assertThat(jdbc.queryForObject("SELECT state FROM purchase_orders WHERE id = ?", String.class, order))
                .isEqualTo("DRAFT");
    }

    @Test
    void aDisabledStandardWhoKeepsTheGrantCannotCreateAndNoOrderIsPersisted() {
        // Arrange
        jdbc.update("UPDATE users SET account_status = 'DISABLED' WHERE id = ?", STANDARD);
        long ordersBefore = count("SELECT count(*) FROM purchase_orders");

        // Act
        Throwable thrown = catchThrowable(() -> createOrder.create(STANDARD, SUPPLIER));

        // Assert
        assertThat(thrown).isInstanceOf(PurchaseOrderActorNotAuthorizedException.class);
        assertThat(count("SELECT count(*) FROM purchase_orders")).isEqualTo(ordersBefore);
        assertThat(count("SELECT count(*) FROM user_permissions WHERE user_id = ?", STANDARD))
                .as("the grant is still persisted: only the account status blocks the creation").isOne();
    }

    @Test
    void aPendingActivationStandardWithTheGrantCannotCreate() {
        // Arrange
        jdbc.update("UPDATE users SET account_status = 'PENDING_ACTIVATION' WHERE id = ?", STANDARD);
        long ordersBefore = count("SELECT count(*) FROM purchase_orders");

        // Act
        Throwable thrown = catchThrowable(() -> createOrder.create(STANDARD, SUPPLIER));

        // Assert
        assertThat(thrown).isInstanceOf(PurchaseOrderActorNotAuthorizedException.class);
        assertThat(count("SELECT count(*) FROM purchase_orders")).isEqualTo(ordersBefore);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
