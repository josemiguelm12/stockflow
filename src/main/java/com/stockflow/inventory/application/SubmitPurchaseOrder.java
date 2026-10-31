package com.stockflow.inventory.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.inventory.domain.PurchaseOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** P3-F1: un STANDARD o ADMIN activo envía una orden ({@code DRAFT -> SUBMITTED}); la orden debe tener proveedor. */
@Service
public class SubmitPurchaseOrder {

    private final UserAdministrationRepository users;
    private final PurchaseOrderRepository orders;

    SubmitPurchaseOrder(UserAdministrationRepository users, PurchaseOrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    /** El bloqueo de fila serializa transiciones concurrentes: quien espera relee el estado ya cambiado. */
    @Transactional
    public void submit(UUID actorId, UUID orderId) {
        PurchaseOrderActors.requireActiveUser(users, actorId);
        PurchaseOrder order = orders.lockById(orderId).orElseThrow(PurchaseOrderNotFoundException::new);
        order.submit();
        orders.save(order);
    }
}
