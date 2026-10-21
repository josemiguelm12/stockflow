package com.stockflow.inventory.application;

/** El actor no tiene concedido {@code PURCHASE_ORDER_CREATE}; ningún rol lo sustituye. */
public class PurchaseOrderPermissionDeniedException extends RuntimeException {

    public PurchaseOrderPermissionDeniedException() {
        super("Missing permission to create purchase orders");
    }
}
