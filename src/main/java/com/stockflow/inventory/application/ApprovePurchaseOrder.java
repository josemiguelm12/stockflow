package com.stockflow.inventory.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.inventory.domain.PurchaseOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.APPROVED;

/** P3-F1: solo un ADMIN activo aprueba una orden ({@code SUBMITTED -> APPROVED}). */
@Service
public class ApprovePurchaseOrder {

    private final UserAdministrationRepository users;
    private final PurchaseOrderRepository orders;

    ApprovePurchaseOrder(UserAdministrationRepository users, PurchaseOrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @Transactional
    public void approve(UUID actorId, UUID orderId) {
        PurchaseOrderActors.requireActiveAdmin(users, actorId);
        PurchaseOrder order = orders.lockById(orderId).orElseThrow(PurchaseOrderNotFoundException::new);
        order.transitionTo(APPROVED);
        orders.save(order);
    }
}
