package com.stockflow.inventory.application;

import java.util.UUID;

/**
 * Puerto de avisos del ciclo de órdenes (P5). Se invoca dentro de la transacción del caso de uso, tras guardar la
 * orden; nunca contacta SMTP y cualquier fallo revierte la transición.
 */
public interface PurchaseOrderEventNotifier {

    /** RF-NOT-03: avisa al propietario de que su orden pasó de SUBMITTED a APPROVED. */
    void orderApproved(UUID orderId, UUID ownerId);
}
