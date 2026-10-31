package com.stockflow.inventory.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.identity.application.UserAdministrationRepository.ManagedUser;
import com.stockflow.inventory.domain.InvalidPurchaseOrderTransitionException;
import com.stockflow.inventory.domain.PurchaseOrder;
import com.stockflow.inventory.domain.PurchaseOrderStatus;
import com.stockflow.inventory.domain.PurchaseOrderSupplierRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.APPROVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.CANCELLED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.DRAFT;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.RECEIVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P3-F1: enviar, aprobar, recibir y cancelar revalidan el actor persistido y conservan el estado ante un rechazo. */
class PurchaseOrderTransitionUseCasesTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000c301");
    private static final UUID STANDARD = UUID.fromString("00000000-0000-0000-0000-00000000c302");
    private static final UUID DISABLED_ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000c303");
    private static final UUID DISABLED_STANDARD = UUID.fromString("00000000-0000-0000-0000-00000000c304");
    private static final UUID UNKNOWN_USER = UUID.fromString("00000000-0000-0000-0000-00000000c305");
    private static final UUID SUPPLIER = UUID.fromString("00000000-0000-0000-0000-00000000d301");
    private static final Instant CREATED_AT = Instant.parse("2026-05-01T10:00:00Z");

    private final UserAdministrationRepository users = mock(UserAdministrationRepository.class);
    private final FakeOrders orders = new FakeOrders();
    private final SubmitPurchaseOrder submit = new SubmitPurchaseOrder(users, orders);
    private final ApprovePurchaseOrder approve = new ApprovePurchaseOrder(users, orders);
    private final ReceivePurchaseOrder receive = new ReceivePurchaseOrder(users, orders);
    private final CancelPurchaseOrder cancel = new CancelPurchaseOrder(users, orders);

    @BeforeEach
    void actors() {
        when(users.find(any())).thenReturn(Optional.empty());
        user(ADMIN, "ADMIN", "ACTIVE");
        user(STANDARD, "STANDARD", "ACTIVE");
        user(DISABLED_ADMIN, "ADMIN", "DISABLED");
        user(DISABLED_STANDARD, "STANDARD", "DISABLED");
    }

    // ---- SubmitPurchaseOrder ----

    @Test
    void aStandardOrAdminSubmitsADraftWithSupplier() {
        for (UUID actor : new UUID[]{STANDARD, ADMIN}) {
            // Arrange
            UUID id = orders.seed(DRAFT, SUPPLIER);

            // Act
            submit.submit(actor, id);

            // Assert
            assertThat(orders.stored(id).status()).isEqualTo(SUBMITTED);
            assertThat(orders.stored(id).supplierId()).contains(SUPPLIER);
            assertThat(orders.locksOf(id)).isEqualTo(1);
            assertThat(orders.savesOf(id)).isEqualTo(1);
        }
    }

    /** Detector P3-3 en aplicación: sin proveedor, el envío se rechaza, la orden sigue en DRAFT y no se guarda. */
    @Test
    void submittingADraftWithoutSupplierIsRejectedAndKeepsDraft() {
        // Arrange
        UUID id = orders.seed(DRAFT, null);

        // Act
        Throwable thrown = catchThrowable(() -> submit.submit(STANDARD, id));

        // Assert
        assertThat(thrown).isInstanceOf(PurchaseOrderSupplierRequiredException.class);
        assertThat(orders.stored(id).status()).isEqualTo(DRAFT);
        assertThat(orders.savesOf(id)).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseOrderStatus.class, names = {"SUBMITTED", "APPROVED", "RECEIVED", "CANCELLED"})
    void onlyADraftCanBeSubmitted(PurchaseOrderStatus from) {
        // Arrange
        UUID id = orders.seed(from, SUPPLIER);

        // Act
        Throwable thrown = catchThrowable(() -> submit.submit(ADMIN, id));

        // Assert
        assertUnchanged(thrown, InvalidPurchaseOrderTransitionException.class, id, from);
    }

    // ---- ApprovePurchaseOrder ----

    @Test
    void anActiveAdminApprovesASubmittedOrder() {
        // Arrange
        UUID id = orders.seed(SUBMITTED, SUPPLIER);

        // Act
        approve.approve(ADMIN, id);

        // Assert
        assertThat(orders.stored(id).status()).isEqualTo(APPROVED);
        assertThat(orders.locksOf(id)).isEqualTo(1);
        assertThat(orders.savesOf(id)).isEqualTo(1);
    }

    @Test
    void aStandardDisabledOrUnknownActorCannotApprove() {
        for (UUID actor : new UUID[]{STANDARD, DISABLED_ADMIN, UNKNOWN_USER}) {
            // Arrange
            UUID id = orders.seed(SUBMITTED, SUPPLIER);

            // Act
            Throwable thrown = catchThrowable(() -> approve.approve(actor, id));

            // Assert
            assertUnchanged(thrown, PurchaseOrderActorNotAuthorizedException.class, id, SUBMITTED);
            assertThat(orders.locksOf(id)).as("authorization precedes the lock").isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseOrderStatus.class, names = {"DRAFT", "APPROVED", "RECEIVED", "CANCELLED"})
    void onlyASubmittedOrderCanBeApproved(PurchaseOrderStatus from) {
        // Arrange
        UUID id = orders.seed(from, SUPPLIER);

        // Act
        Throwable thrown = catchThrowable(() -> approve.approve(ADMIN, id));

        // Assert
        assertUnchanged(thrown, InvalidPurchaseOrderTransitionException.class, id, from);
    }

    // ---- ReceivePurchaseOrder ----

    @Test
    void anActiveAdminReceivesAnApprovedOrder() {
        // Arrange
        UUID id = orders.seed(APPROVED, SUPPLIER);

        // Act
        receive.receive(ADMIN, id);

        // Assert
        assertThat(orders.stored(id).status()).isEqualTo(RECEIVED);
        assertThat(orders.locksOf(id)).isEqualTo(1);
        assertThat(orders.savesOf(id)).isEqualTo(1);
    }

    @Test
    void aStandardDisabledOrUnknownActorCannotReceive() {
        for (UUID actor : new UUID[]{STANDARD, DISABLED_ADMIN, UNKNOWN_USER}) {
            // Arrange
            UUID id = orders.seed(APPROVED, SUPPLIER);

            // Act
            Throwable thrown = catchThrowable(() -> receive.receive(actor, id));

            // Assert
            assertUnchanged(thrown, PurchaseOrderActorNotAuthorizedException.class, id, APPROVED);
        }
    }

    /** Detector P3-1 en aplicación: recibir una orden SUBMITTED no puede saltarse la aprobación. */
    @Test
    void aSubmittedOrderCannotBeReceivedWithoutApproval() {
        // Arrange
        UUID id = orders.seed(SUBMITTED, SUPPLIER);

        // Act
        Throwable thrown = catchThrowable(() -> receive.receive(ADMIN, id));

        // Assert
        assertUnchanged(thrown, InvalidPurchaseOrderTransitionException.class, id, SUBMITTED);
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseOrderStatus.class, names = {"DRAFT", "RECEIVED", "CANCELLED"})
    void onlyAnApprovedOrderCanBeReceived(PurchaseOrderStatus from) {
        // Arrange
        UUID id = orders.seed(from, SUPPLIER);

        // Act
        Throwable thrown = catchThrowable(() -> receive.receive(ADMIN, id));

        // Assert
        assertUnchanged(thrown, InvalidPurchaseOrderTransitionException.class, id, from);
    }

    // ---- CancelPurchaseOrder ----

    @Test
    void aStandardOrAdminCancelsADraft() {
        for (UUID actor : new UUID[]{STANDARD, ADMIN}) {
            // Arrange
            UUID id = orders.seed(DRAFT, null);

            // Act
            cancel.cancel(actor, id);

            // Assert
            assertThat(orders.stored(id).status()).isEqualTo(CANCELLED);
            assertThat(orders.savesOf(id)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseOrderStatus.class, names = {"SUBMITTED", "APPROVED"})
    void onlyAnAdminCancelsAnOrderThatLeftDraft(PurchaseOrderStatus from) {
        // Arrange
        UUID byStandard = orders.seed(from, SUPPLIER);
        UUID byAdmin = orders.seed(from, SUPPLIER);

        // Act
        Throwable thrown = catchThrowable(() -> cancel.cancel(STANDARD, byStandard));
        cancel.cancel(ADMIN, byAdmin);

        // Assert
        assertUnchanged(thrown, PurchaseOrderActorNotAuthorizedException.class, byStandard, from);
        assertThat(orders.stored(byAdmin).status()).isEqualTo(CANCELLED);
    }

    @Test
    void aDisabledOrUnknownActorCannotCancelEvenADraft() {
        for (UUID actor : new UUID[]{DISABLED_STANDARD, DISABLED_ADMIN, UNKNOWN_USER}) {
            // Arrange
            UUID id = orders.seed(DRAFT, SUPPLIER);

            // Act
            Throwable thrown = catchThrowable(() -> cancel.cancel(actor, id));

            // Assert
            assertUnchanged(thrown, PurchaseOrderActorNotAuthorizedException.class, id, DRAFT);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseOrderStatus.class, names = {"RECEIVED", "CANCELLED"})
    void aTerminalOrderCannotBeCancelled(PurchaseOrderStatus terminal) {
        // Arrange
        UUID id = orders.seed(terminal, SUPPLIER);

        // Act
        Throwable thrown = catchThrowable(() -> cancel.cancel(ADMIN, id));

        // Assert
        assertUnchanged(thrown, InvalidPurchaseOrderTransitionException.class, id, terminal);
    }

    // ---- comunes ----

    @Test
    void everyTransitionOfAnUnknownOrderIsNotFoundAndSavesNothing() {
        // Arrange
        UUID missing = UUID.randomUUID();

        // Act
        Throwable submitted = catchThrowable(() -> submit.submit(ADMIN, missing));
        Throwable approved = catchThrowable(() -> approve.approve(ADMIN, missing));
        Throwable received = catchThrowable(() -> receive.receive(ADMIN, missing));
        Throwable cancelled = catchThrowable(() -> cancel.cancel(ADMIN, missing));

        // Assert
        assertThat(submitted).isInstanceOf(PurchaseOrderNotFoundException.class);
        assertThat(approved).isInstanceOf(PurchaseOrderNotFoundException.class);
        assertThat(received).isInstanceOf(PurchaseOrderNotFoundException.class);
        assertThat(cancelled).isInstanceOf(PurchaseOrderNotFoundException.class);
        assertThat(orders.totalSaves()).isZero();
    }

    @Test
    void aDisabledOrUnknownActorCannotSubmit() {
        for (UUID actor : new UUID[]{DISABLED_STANDARD, UNKNOWN_USER}) {
            // Arrange
            UUID id = orders.seed(DRAFT, SUPPLIER);

            // Act
            Throwable thrown = catchThrowable(() -> submit.submit(actor, id));

            // Assert
            assertUnchanged(thrown, PurchaseOrderActorNotAuthorizedException.class, id, DRAFT);
        }
    }

    @Test
    void theFullLifecycleReachesReceivedThroughTheUseCases() {
        // Arrange
        UUID id = orders.seed(DRAFT, SUPPLIER);

        // Act
        submit.submit(STANDARD, id);
        approve.approve(ADMIN, id);
        receive.receive(ADMIN, id);

        // Assert
        assertThat(orders.stored(id).status()).isEqualTo(RECEIVED);
        assertThat(orders.savesOf(id)).isEqualTo(3);
        assertThat(catchThrowable(() -> cancel.cancel(ADMIN, id)))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        assertThat(orders.stored(id).status()).isEqualTo(RECEIVED);
    }

    // ---- utilidades ----

    private void user(UUID id, String role, String status) {
        when(users.find(id)).thenReturn(Optional.of(new ManagedUser(id, id + "@example.test", role, status, true)));
    }

    private void assertUnchanged(Throwable thrown, Class<? extends Throwable> expected, UUID id,
                                 PurchaseOrderStatus status) {
        assertThat(thrown).isInstanceOf(expected);
        assertThat(orders.stored(id).status()).isEqualTo(status);
        assertThat(orders.savesOf(id)).isZero();
    }

    /** Guarda copias: el caso de uso solo cambia lo persistido llamando a {@code save}. */
    private static final class FakeOrders implements PurchaseOrderRepository {

        private final Map<UUID, PurchaseOrder> rows = new HashMap<>();
        private final Map<UUID, Integer> saves = new HashMap<>();
        private final Map<UUID, Integer> locks = new HashMap<>();

        UUID seed(PurchaseOrderStatus status, UUID supplierId) {
            UUID id = UUID.randomUUID();
            rows.put(id, PurchaseOrder.restore(id, supplierId, status, CREATED_AT));
            return id;
        }

        PurchaseOrder stored(UUID id) {
            return copy(rows.get(id));
        }

        int savesOf(UUID id) {
            return saves.getOrDefault(id, 0);
        }

        int locksOf(UUID id) {
            return locks.getOrDefault(id, 0);
        }

        int totalSaves() {
            return saves.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public void save(PurchaseOrder order) {
            saves.merge(order.id(), 1, Integer::sum);
            rows.put(order.id(), copy(order));
        }

        @Override
        public Optional<PurchaseOrder> findById(UUID id) {
            return Optional.ofNullable(rows.get(id)).map(FakeOrders::copy);
        }

        @Override
        public Optional<PurchaseOrder> lockById(UUID id) {
            locks.merge(id, 1, Integer::sum);
            return findById(id);
        }

        private static PurchaseOrder copy(PurchaseOrder order) {
            return PurchaseOrder.restore(order.id(), order.supplierId().orElse(null), order.status(),
                    order.createdAt());
        }
    }
}
