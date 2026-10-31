package com.stockflow.inventory.application;

/** No existe una orden con ese identificador; el mensaje no lo incluye. */
public class PurchaseOrderNotFoundException extends RuntimeException {

    public PurchaseOrderNotFoundException() {
        super("Purchase order not found");
    }
}
