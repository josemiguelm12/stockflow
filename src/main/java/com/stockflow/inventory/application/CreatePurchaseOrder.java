package com.stockflow.inventory.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.inventory.domain.PurchaseOrder;
import com.stockflow.permission.application.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;

/**
 * Crea una orden en {@code DRAFT} solo si el actor persistido existe, está {@code ACTIVE}, es STANDARD o ADMIN y
 * además tiene concedido {@code PURCHASE_ORDER_CREATE} (RF-GP-05). Ambas comprobaciones usan datos del servidor y se
 * hacen antes de construir o guardar nada, así que también protegen una invocación directa sin HTTP; ser ADMIN no
 * sustituye al permiso, y conservar el permiso no basta a un usuario deshabilitado.
 */
@Service
public class CreatePurchaseOrder {

    private final UserAdministrationRepository users;
    private final PermissionChecker permissions;
    private final PurchaseOrderRepository orders;
    private final Clock clock;

    CreatePurchaseOrder(UserAdministrationRepository users, PermissionChecker permissions,
                        PurchaseOrderRepository orders, Clock clock) {
        this.users = users;
        this.permissions = permissions;
        this.orders = orders;
        this.clock = clock;
    }

    /** {@code supplierId} puede ser nulo, como en {@link PurchaseOrder#create}. */
    @Transactional
    public UUID create(UUID actorId, UUID supplierId) {
        PurchaseOrderActors.requireActiveUser(users, actorId);
        PurchaseOrder order = PurchaseOrder.create(UUID.randomUUID(), supplierId, clock.instant());
        orders.save(order);
        return order.id();
    }
}
