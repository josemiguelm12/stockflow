package com.stockflow.inventory.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.inventory.domain.PurchaseOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.RECEIVED;

/** P3-F1: solo un ADMIN activo recibe una orden ({@code APPROVED -> RECEIVED}); no mueve stock. */
@Service
public class ReceivePurchaseOrder {

    private final UserAdministrationRepository users;
    private final PurchaseOrderRepository orders;

    ReceivePurchaseOrder(UserAdministrationRepository users, PurchaseOrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @Transactional
    public void receive(UUID actorId, UUID orderId) {
        PurchaseOrderActors.requireActiveAdmin(users, actorId);
        PurchaseOrder order = orders.lockById(orderId).orElseThrow(PurchaseOrderNotFoundException::new);
        order.transitionTo(RECEIVED);
        orders.save(order);
    }
}
