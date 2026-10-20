package com.stockflow.inventory.application;

import com.stockflow.inventory.domain.PurchaseOrder;
import com.stockflow.permission.application.GrantedPermission;
import com.stockflow.permission.application.PermissionChecker;
import com.stockflow.permission.application.UserPermissionRepository;
import com.stockflow.permission.domain.PermissionCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.DRAFT;
import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** RF-GP-05: crear una orden exige la concesión persistida de PURCHASE_ORDER_CREATE; ningún rol la sustituye. */
class CreatePurchaseOrderTest {

    private static final UUID GRANTED_USER = UUID.fromString("00000000-0000-0000-0000-00000000a401");
    private static final UUID USER_WITHOUT_GRANT = UUID.fromString("00000000-0000-0000-0000-00000000a402");
    private static final UUID SOURCE_REQUEST = UUID.fromString("00000000-0000-0000-0000-00000000b401");
    private static final UUID SUPPLIER = UUID.fromString("00000000-0000-0000-0000-00000000d401");
    private static final Instant NOW = Instant.parse("2026-04-03T08:30:00Z");

    private final FakeGrants grants = new FakeGrants();
    private final FakeOrders orders = new FakeOrders();
    private final CreatePurchaseOrder createOrder = new CreatePurchaseOrder(new PermissionChecker(grants), orders,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aUserWithoutTheGrantIsRejectedBeforeAnyOrderIsSaved() {
        // Arrange
        // (no grant for USER_WITHOUT_GRANT)

        // Act
        Throwable thrown = catchThrowable(() -> createOrder.create(USER_WITHOUT_GRANT, SUPPLIER));

        // Assert
        assertThat(thrown).isInstanceOf(PurchaseOrderPermissionDeniedException.class);
        assertThat(grants.checks).containsExactly(USER_WITHOUT_GRANT + ":" + PURCHASE_ORDER_CREATE);
        assertThat(orders.rows).isEmpty();
    }

    @Test
    void aUserWithThePersistedGrantCreatesADraftOrder() {
        // Arrange
        grants.add(new GrantedPermission(UUID.randomUUID(), GRANTED_USER, PURCHASE_ORDER_CREATE, SOURCE_REQUEST,
                NOW));

        // Act
        UUID id = createOrder.create(GRANTED_USER, SUPPLIER);

        // Assert
        assertThat(orders.rows).containsOnlyKeys(id);
        PurchaseOrder order = orders.rows.get(id);
        assertThat(order.status()).isEqualTo(DRAFT);
        assertThat(order.supplierId()).contains(SUPPLIER);
        assertThat(order.createdAt()).isEqualTo(NOW);
    }

    @Test
    void theSupplierRemainsOptional() {
        // Arrange
        grants.add(new GrantedPermission(UUID.randomUUID(), GRANTED_USER, PURCHASE_ORDER_CREATE, SOURCE_REQUEST,
                NOW));

        // Act
        UUID id = createOrder.create(GRANTED_USER, null);

        // Assert
        assertThat(orders.rows.get(id).supplierId()).isEmpty();
    }

    @Test
    void theGrantOfAnotherUserDoesNotAuthorize() {
        // Arrange
        grants.add(new GrantedPermission(UUID.randomUUID(), GRANTED_USER, PURCHASE_ORDER_CREATE, SOURCE_REQUEST,
                NOW));

        // Act
        Throwable thrown = catchThrowable(() -> createOrder.create(USER_WITHOUT_GRANT, SUPPLIER));

        // Assert
        assertThat(thrown).isInstanceOf(PurchaseOrderPermissionDeniedException.class);
        assertThat(orders.rows).isEmpty();
    }

    private static final class FakeGrants implements UserPermissionRepository {

        private final List<GrantedPermission> rows = new ArrayList<>();
        private final List<String> checks = new ArrayList<>();

        void add(GrantedPermission permission) {
            rows.add(permission);
        }

        @Override
        public void save(GrantedPermission permission) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<GrantedPermission> findBySourceRequestId(UUID sourceRequestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsByUserIdAndPermissionCode(UUID userId, PermissionCode permissionCode) {
            checks.add(userId + ":" + permissionCode);
            return rows.stream().anyMatch(g -> g.userId().equals(userId) && g.permissionCode() == permissionCode);
        }
    }

    private static final class FakeOrders implements PurchaseOrderRepository {

        private final Map<UUID, PurchaseOrder> rows = new HashMap<>();

        @Override
        public void save(PurchaseOrder order) {
            rows.put(order.id(), order);
        }

        @Override
        public Optional<PurchaseOrder> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }
    }
}
