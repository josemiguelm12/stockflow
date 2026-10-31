package com.stockflow.inventory.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.APPROVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.CANCELLED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.DRAFT;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.RECEIVED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class PurchaseOrderTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void aNewOrderStartsInDraft() {
        UUID id = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        PurchaseOrder order = PurchaseOrder.create(id, supplierId, CREATED_AT);

        assertThat(order.id()).isEqualTo(id);
        assertThat(order.supplierId()).contains(supplierId);
        assertThat(order.status()).isEqualTo(DRAFT);
        assertThat(order.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void theSupplierIsOptionalButTheOtherAttributesAreRequired() {
        assertThat(PurchaseOrder.create(UUID.randomUUID(), null, CREATED_AT).supplierId()).isEmpty();

        assertThatThrownBy(() -> PurchaseOrder.create(null, null, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PurchaseOrder.create(UUID.randomUUID(), null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PurchaseOrder.restore(UUID.randomUUID(), null, null, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theHappyPathReachesReceived() {
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), null, CREATED_AT);

        order.transitionTo(SUBMITTED);
        order.transitionTo(APPROVED);
        order.transitionTo(RECEIVED);

        assertThat(order.status()).isEqualTo(RECEIVED);
    }

    @Test
    void everyNonTerminalStateCanBeCancelled() {
        for (PurchaseOrderStatus from : new PurchaseOrderStatus[]{DRAFT, SUBMITTED, APPROVED}) {
            PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, from, CREATED_AT);
            order.transitionTo(CANCELLED);
            assertThat(order.status()).isEqualTo(CANCELLED);
        }
    }

    @Test
    void aRejectedTransitionLeavesTheStateUnchanged() {
        PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, SUBMITTED, CREATED_AT);

        assertThatThrownBy(() -> order.transitionTo(RECEIVED))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        assertThatThrownBy(() -> order.transitionTo(SUBMITTED))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        assertThatThrownBy(() -> order.transitionTo(null))
                .isInstanceOf(NullPointerException.class);

        assertThat(order.status()).isEqualTo(SUBMITTED);
    }

    @Test
    void submittingADraftWithASupplierMovesItToSubmitted() {
        // Arrange
        UUID supplierId = UUID.randomUUID();
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), supplierId, CREATED_AT);

        // Act
        order.submit();

        // Assert
        assertThat(order.status()).isEqualTo(SUBMITTED);
        assertThat(order.supplierId()).contains(supplierId);
    }

    /** Detector P3-3: una orden sin proveedor existe en DRAFT, pero no puede enviarse. */
    @Test
    void aDraftWithoutSupplierCannotBeSubmittedAndStaysDraft() {
        // Arrange
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), null, CREATED_AT);

        // Act
        Throwable thrown = catchThrowable(order::submit);

        // Assert
        assertThat(thrown).isInstanceOf(PurchaseOrderSupplierRequiredException.class);
        assertThat(order.status()).isEqualTo(DRAFT);
        assertThat(order.supplierId()).isEmpty();
    }

    @Test
    void onlyADraftCanBeSubmittedEvenWithASupplier() {
        for (PurchaseOrderStatus from : new PurchaseOrderStatus[]{SUBMITTED, APPROVED, RECEIVED, CANCELLED}) {
            // Arrange
            PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), UUID.randomUUID(), from, CREATED_AT);

            // Act
            Throwable thrown = catchThrowable(order::submit);

            // Assert
            assertThat(thrown).as(from.name()).isInstanceOf(InvalidPurchaseOrderTransitionException.class);
            assertThat(order.status()).isEqualTo(from);
        }
    }

    @Test
    void aNonDraftWithoutSupplierIsRejectedByThePolicyFirst() {
        // Arrange
        PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, CANCELLED, CREATED_AT);

        // Act
        Throwable thrown = catchThrowable(order::submit);

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        assertThat(order.status()).isEqualTo(CANCELLED);
    }

    @Test
    void terminalOrdersCannotLeaveTheirState() {
        for (PurchaseOrderStatus terminal : new PurchaseOrderStatus[]{RECEIVED, CANCELLED}) {
            PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, terminal, CREATED_AT);
            for (PurchaseOrderStatus target : PurchaseOrderStatus.values()) {
                assertThatThrownBy(() -> order.transitionTo(target))
                        .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
            }
            assertThat(order.status()).isEqualTo(terminal);
        }
    }
}
