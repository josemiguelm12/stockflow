package com.stockflow.inventory.domain;

/**
 * Único lugar donde se declaran los estados de una orden de compra (RF-NEG-03). Las transiciones entre ellos, y por
 * tanto qué estados son terminales, viven solo en {@link PurchaseOrderTransitionPolicy}.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    RECEIVED,
    CANCELLED
}
