package com.stockflow.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orden de compra: entidad central del módulo de negocio (RF-NEG-03). Su estado solo cambia a través de
 * {@link PurchaseOrderTransitionPolicy}.
 */
public final class PurchaseOrder {

    private final UUID id;
    private final UUID supplierId;
    private PurchaseOrderStatus status;
    private final Instant createdAt;

    private PurchaseOrder(UUID id, UUID supplierId, PurchaseOrderStatus status, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.supplierId = supplierId;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Nueva orden; siempre empieza en {@code DRAFT}. {@code supplierId} puede ser nulo. */
    public static PurchaseOrder create(UUID id, UUID supplierId, Instant createdAt) {
        return new PurchaseOrder(id, supplierId, PurchaseOrderStatus.DRAFT, createdAt);
    }

    /** Reconstruye una orden ya persistida en el estado que tenía. */
    public static PurchaseOrder restore(UUID id, UUID supplierId, PurchaseOrderStatus status, Instant createdAt) {
        return new PurchaseOrder(id, supplierId, status, createdAt);
    }

    /** Valida la transición con la política y solo después cambia el estado. */
    public void transitionTo(PurchaseOrderStatus target) {
        PurchaseOrderTransitionPolicy.requireAllowed(status, target);
        status = target;
    }

    public UUID id() {
        return id;
    }

    public Optional<UUID> supplierId() {
        return Optional.ofNullable(supplierId);
    }

    public PurchaseOrderStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
