package com.stockflow.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orden de compra: entidad central del módulo de negocio (RF-NEG-03). Su estado solo cambia a través de
 * {@link PurchaseOrderTransitionPolicy}. Las órdenes nuevas tienen propietario (el actor que las creó) y registran
 * los instantes de envío y aprobación; las históricas pueden carecer de ambos y nunca se les inventan.
 */
public final class PurchaseOrder {

    private final UUID id;
    private final UUID supplierId;
    private final UUID ownerId;
    private PurchaseOrderStatus status;
    private final Instant createdAt;
    private Instant submittedAt;
    private Instant approvedAt;

    private PurchaseOrder(UUID id, UUID supplierId, UUID ownerId, PurchaseOrderStatus status, Instant createdAt,
                          Instant submittedAt, Instant approvedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.supplierId = supplierId;
        this.ownerId = ownerId;
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.submittedAt = submittedAt;
        this.approvedAt = approvedAt;
    }

    /** Nueva orden; siempre empieza en {@code DRAFT} y exige propietario. {@code supplierId} puede ser nulo. */
    public static PurchaseOrder create(UUID id, UUID supplierId, UUID ownerId, Instant createdAt) {
        return new PurchaseOrder(id, supplierId, Objects.requireNonNull(ownerId, "ownerId"),
                PurchaseOrderStatus.DRAFT, createdAt, null, null);
    }

    /** Reconstruye una orden ya persistida; propietario y fechas son nulos en las históricas. */
    public static PurchaseOrder restore(UUID id, UUID supplierId, UUID ownerId, PurchaseOrderStatus status,
                                        Instant createdAt, Instant submittedAt, Instant approvedAt) {
        return new PurchaseOrder(id, supplierId, ownerId, status, createdAt, submittedAt, approvedAt);
    }

    /** Valida la transición con la política y solo después cambia el estado. */
    public void transitionTo(PurchaseOrderStatus target) {
        PurchaseOrderTransitionPolicy.requireAllowed(status, target);
        status = target;
    }

    /**
     * Operación de envío ({@code DRAFT -> SUBMITTED}): además de la política exige proveedor y fija
     * {@code submittedAt}. Primero decide la política (un origen que no sea {@code DRAFT} se rechaza como
     * transición); si falta el proveedor, ni el estado ni la fecha cambian.
     */
    public void submit(Instant now) {
        Objects.requireNonNull(now, "now");
        PurchaseOrderTransitionPolicy.requireAllowed(status, PurchaseOrderStatus.SUBMITTED);
        if (supplierId == null) {
            throw new PurchaseOrderSupplierRequiredException();
        }
        transitionTo(PurchaseOrderStatus.SUBMITTED);
        submittedAt = now;
    }

    /**
     * Operación de aprobación ({@code SUBMITTED -> APPROVED}): fija {@code approvedAt}, nunca anterior a
     * {@code submittedAt} aunque el reloj retroceda. Una transición inválida no cambia nada.
     */
    public void approve(Instant now) {
        Objects.requireNonNull(now, "now");
        transitionTo(PurchaseOrderStatus.APPROVED);
        approvedAt = submittedAt != null && now.isBefore(submittedAt) ? submittedAt : now;
    }

    public UUID id() {
        return id;
    }

    public Optional<UUID> supplierId() {
        return Optional.ofNullable(supplierId);
    }

    /** Vacío solo en órdenes históricas, que no se atribuyen a ningún usuario. */
    public Optional<UUID> ownerId() {
        return Optional.ofNullable(ownerId);
    }

    public PurchaseOrderStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> submittedAt() {
        return Optional.ofNullable(submittedAt);
    }

    public Optional<Instant> approvedAt() {
        return Optional.ofNullable(approvedAt);
    }
}
