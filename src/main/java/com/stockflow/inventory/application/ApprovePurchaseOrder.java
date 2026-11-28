package com.stockflow.inventory.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.inventory.domain.PurchaseOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * P3-F1: solo un ADMIN activo aprueba una orden ({@code SUBMITTED -> APPROVED}). P5: registra {@code approvedAt} y
 * avisa al propietario en la misma transacción.
 */
@Service
public class ApprovePurchaseOrder {

    private final UserAdministrationRepository users;
    private final PurchaseOrderRepository orders;
    private final Clock clock;
    private final PurchaseOrderEventNotifier notifier;

    ApprovePurchaseOrder(UserAdministrationRepository users, PurchaseOrderRepository orders, Clock clock,
                         PurchaseOrderEventNotifier notifier) {
        this.users = users;
        this.orders = orders;
        this.clock = clock;
        this.notifier = notifier;
    }

    @Transactional
    public void approve(UUID actorId, UUID orderId) {
        PurchaseOrderActors.requireActiveAdmin(users, actorId);
        PurchaseOrder order = orders.lockById(orderId).orElseThrow(PurchaseOrderNotFoundException::new);
        order.approve(clock.instant());
        orders.save(order);
        // Orden histórica sin propietario: no hay destinatario y no se atribuye a nadie.
        order.ownerId().ifPresent(owner -> notifier.orderApproved(order.id(), owner));
    }
}
