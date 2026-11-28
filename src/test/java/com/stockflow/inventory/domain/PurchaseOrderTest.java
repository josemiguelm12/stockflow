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
    private static final Instant NOW = Instant.parse("2026-01-02T00:00:00Z");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000e501");

    @Test
    void aNewOrderStartsInDraft() {
        UUID id = UUID.randomUUID();
        UUID supplierId = UUID.randomUUID();

        PurchaseOrder order = PurchaseOrder.create(id, supplierId, OWNER, CREATED_AT);

        assertThat(order.id()).isEqualTo(id);
        assertThat(order.supplierId()).contains(supplierId);
        assertThat(order.status()).isEqualTo(DRAFT);
        assertThat(order.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void theSupplierIsOptionalButTheOtherAttributesAreRequired() {
        assertThat(PurchaseOrder.create(UUID.randomUUID(), null, OWNER, CREATED_AT).supplierId()).isEmpty();

        assertThatThrownBy(() -> PurchaseOrder.create(null, null, OWNER, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PurchaseOrder.create(UUID.randomUUID(), null, OWNER, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PurchaseOrder.restore(UUID.randomUUID(), null, OWNER, null, CREATED_AT, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theHappyPathReachesReceived() {
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), null, OWNER, CREATED_AT);

        order.transitionTo(SUBMITTED);
        order.transitionTo(APPROVED);
        order.transitionTo(RECEIVED);

        assertThat(order.status()).isEqualTo(RECEIVED);
    }

    @Test
    void everyNonTerminalStateCanBeCancelled() {
        for (PurchaseOrderStatus from : new PurchaseOrderStatus[]{DRAFT, SUBMITTED, APPROVED}) {
            PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, OWNER, from, CREATED_AT, null, null);
            order.transitionTo(CANCELLED);
            assertThat(order.status()).isEqualTo(CANCELLED);
        }
    }

    @Test
    void aRejectedTransitionLeavesTheStateUnchanged() {
        PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, OWNER, SUBMITTED, CREATED_AT, null, null);

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
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), supplierId, OWNER, CREATED_AT);

        // Act
        order.submit(NOW);

        // Assert
        assertThat(order.status()).isEqualTo(SUBMITTED);
        assertThat(order.supplierId()).contains(supplierId);
    }

    /** Detector P3-3: una orden sin proveedor existe en DRAFT, pero no puede enviarse. */
    @Test
    void aDraftWithoutSupplierCannotBeSubmittedAndStaysDraft() {
        // Arrange
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), null, OWNER, CREATED_AT);

        // Act
        Throwable thrown = catchThrowable(() -> order.submit(NOW));

        // Assert
        assertThat(thrown).isInstanceOf(PurchaseOrderSupplierRequiredException.class);
        assertThat(order.status()).isEqualTo(DRAFT);
        assertThat(order.supplierId()).isEmpty();
    }

    @Test
    void onlyADraftCanBeSubmittedEvenWithASupplier() {
        for (PurchaseOrderStatus from : new PurchaseOrderStatus[]{SUBMITTED, APPROVED, RECEIVED, CANCELLED}) {
            // Arrange
            PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), UUID.randomUUID(), OWNER, from, CREATED_AT, null, null);

            // Act
            Throwable thrown = catchThrowable(() -> order.submit(NOW));

            // Assert
            assertThat(thrown).as(from.name()).isInstanceOf(InvalidPurchaseOrderTransitionException.class);
            assertThat(order.status()).isEqualTo(from);
        }
    }

    @Test
    void aNonDraftWithoutSupplierIsRejectedByThePolicyFirst() {
        // Arrange
        PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, OWNER, CANCELLED, CREATED_AT, null, null);

        // Act
        Throwable thrown = catchThrowable(() -> order.submit(NOW));

        // Assert
        assertThat(thrown).isInstanceOf(InvalidPurchaseOrderTransitionException.class);
        assertThat(order.status()).isEqualTo(CANCELLED);
    }

    @Test
    void terminalOrdersCannotLeaveTheirState() {
        for (PurchaseOrderStatus terminal : new PurchaseOrderStatus[]{RECEIVED, CANCELLED}) {
            PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, OWNER, terminal, CREATED_AT, null, null);
            for (PurchaseOrderStatus target : PurchaseOrderStatus.values()) {
                assertThatThrownBy(() -> order.transitionTo(target))
                        .isInstanceOf(InvalidPurchaseOrderTransitionException.class);
            }
            assertThat(order.status()).isEqualTo(terminal);
        }
    }

    // ---- P5: propietario y fechas de transición ----

    @Test
    void aNewOrderRequiresAnOwnerAndHasNoTransitionDates() {
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), null, OWNER, CREATED_AT);

        assertThat(order.ownerId()).contains(OWNER);
        assertThat(order.submittedAt()).isEmpty();
        assertThat(order.approvedAt()).isEmpty();
        assertThatThrownBy(() -> PurchaseOrder.create(UUID.randomUUID(), null, null, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aHistoricalOrderMayBeRestoredWithoutOwnerNorDates() {
        PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, null, SUBMITTED, CREATED_AT, null, null);

        assertThat(order.ownerId()).isEmpty();
        assertThat(order.submittedAt()).isEmpty();
        assertThat(order.approvedAt()).isEmpty();
    }

    @Test
    void submittingRecordsSubmittedAtAndApprovingRecordsApprovedAt() {
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), UUID.randomUUID(), OWNER, CREATED_AT);
        Instant approvedAt = NOW.plusSeconds(90);

        order.submit(NOW);
        order.approve(approvedAt);

        assertThat(order.status()).isEqualTo(APPROVED);
        assertThat(order.submittedAt()).contains(NOW);
        assertThat(order.approvedAt()).contains(approvedAt);
    }

    @Test
    void aFailedSubmitDoesNotRecordSubmittedAt() {
        PurchaseOrder withoutSupplier = PurchaseOrder.create(UUID.randomUUID(), null, OWNER, CREATED_AT);
        PurchaseOrder alreadySubmitted = PurchaseOrder.restore(UUID.randomUUID(), UUID.randomUUID(), OWNER, SUBMITTED,
                CREATED_AT, NOW, null);

        assertThatThrownBy(() -> withoutSupplier.submit(NOW))
                .isInstanceOf(PurchaseOrderSupplierRequiredException.class);
        assertThatThrownBy(() -> alreadySubmitted.submit(NOW.plusSeconds(5)))
                .isInstanceOf(InvalidPurchaseOrderTransitionException.class);

        assertThat(withoutSupplier.submittedAt()).isEmpty();
        assertThat(alreadySubmitted.submittedAt()).contains(NOW);
    }

    @Test
    void approvingFromAnyStateButSubmittedIsRejectedWithoutRecordingApprovedAt() {
        for (PurchaseOrderStatus from : new PurchaseOrderStatus[]{DRAFT, APPROVED, RECEIVED, CANCELLED}) {
            PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, OWNER, from, CREATED_AT, null, null);

            assertThatThrownBy(() -> order.approve(NOW)).as(from.name())
                    .isInstanceOf(InvalidPurchaseOrderTransitionException.class);

            assertThat(order.status()).isEqualTo(from);
            assertThat(order.approvedAt()).isEmpty();
        }
    }

    @Test
    void approvedAtIsNeverEarlierThanSubmittedAtEvenIfTheClockMovesBack() {
        PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), UUID.randomUUID(), OWNER, SUBMITTED, CREATED_AT,
                NOW, null);

        order.approve(NOW.minusSeconds(30));

        assertThat(order.approvedAt()).contains(NOW);
    }

    @Test
    void approvingAHistoricalOrderRecordsApprovalButInventsNoSubmissionDateOrOwner() {
        PurchaseOrder order = PurchaseOrder.restore(UUID.randomUUID(), null, null, SUBMITTED, CREATED_AT, null, null);

        order.approve(NOW);

        assertThat(order.approvedAt()).contains(NOW);
        assertThat(order.submittedAt()).isEmpty();
        assertThat(order.ownerId()).isEmpty();
    }
}
