package com.stockflow.inventory.application;

/**
 * El actor no existe, no está activo o su rol persistido no basta para la transición pedida. El mensaje no incluye
 * identificadores ni el rol.
 */
public class PurchaseOrderActorNotAuthorizedException extends RuntimeException {

    public PurchaseOrderActorNotAuthorizedException() {
        super("Actor is not authorized for this purchase order operation");
    }
}
