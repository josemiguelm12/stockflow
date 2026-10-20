package com.stockflow.inventory.application;

import com.stockflow.inventory.domain.PurchaseOrder;
import com.stockflow.permission.application.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;

/**
 * Crea una orden en {@code DRAFT} solo si el actor tiene concedido {@code PURCHASE_ORDER_CREATE} (RF-GP-05). El
 * permiso se comprueba antes de construir o guardar nada; ser ADMIN no lo sustituye.
 */
@Service
public class CreatePurchaseOrder {

    private final PermissionChecker permissions;
    private final PurchaseOrderRepository orders;
    private final Clock clock;

    CreatePurchaseOrder(PermissionChecker permissions, PurchaseOrderRepository orders, Clock clock) {
        this.permissions = permissions;
        this.orders = orders;
        this.clock = clock;
    }

    /** {@code supplierId} puede ser nulo, como en {@link PurchaseOrder#create}. */
    @Transactional
    public UUID create(UUID actorId, UUID supplierId) {
        if (!permissions.hasPermission(actorId, PURCHASE_ORDER_CREATE)) {
            throw new PurchaseOrderPermissionDeniedException();
        }
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), supplierId, clock.instant());
        orders.save(order);
        return order.id();
    }
}
