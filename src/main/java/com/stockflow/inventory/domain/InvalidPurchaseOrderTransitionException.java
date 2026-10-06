package com.stockflow.inventory.domain;

/** Transición de estado que la política no permite: par ausente, salida de un estado terminal o mismo estado. */
public class InvalidPurchaseOrderTransitionException extends RuntimeException {

    private final PurchaseOrderStatus from;
    private final PurchaseOrderStatus to;

    public InvalidPurchaseOrderTransitionException(PurchaseOrderStatus from, PurchaseOrderStatus to) {
        super("Purchase order transition not allowed: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public PurchaseOrderStatus from() {
        return from;
    }

    public PurchaseOrderStatus to() {
        return to;
    }
}
