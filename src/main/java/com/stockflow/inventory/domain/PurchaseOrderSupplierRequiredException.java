package com.stockflow.inventory.domain;

/** Una orden sin proveedor puede existir en {@code DRAFT}, pero no puede enviarse (P3-F1). */
public class PurchaseOrderSupplierRequiredException extends RuntimeException {

    public PurchaseOrderSupplierRequiredException() {
        super("A purchase order needs a supplier to be submitted");
    }
}
