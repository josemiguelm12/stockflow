package com.stockflow.inventory.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.identity.application.UserAdministrationRepository.ManagedUser;
import com.stockflow.inventory.domain.PurchaseOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.stockflow.inventory.domain.PurchaseOrderStatus.CANCELLED;
import static com.stockflow.inventory.domain.PurchaseOrderStatus.DRAFT;

/**
 * P3-F1: cancela una orden ({@code DRAFT|SUBMITTED|APPROVED -> CANCELLED}). La ruta admite STANDARD o ADMIN; aquí se
 * revalida la política de rol con el estado bloqueado: un borrador lo cancela cualquiera de los dos, una orden que ya
 * salió de {@code DRAFT} solo un ADMIN activo.
 */
@Service
public class CancelPurchaseOrder {

    private final UserAdministrationRepository users;
    private final PurchaseOrderRepository orders;

    CancelPurchaseOrder(UserAdministrationRepository users, PurchaseOrderRepository orders) {
        this.users = users;
        this.orders = orders;
    }

    @Transactional
    public void cancel(UUID actorId, UUID orderId) {
        ManagedUser actor = PurchaseOrderActors.requireActiveUser(users, actorId);
        PurchaseOrder order = orders.lockById(orderId).orElseThrow(PurchaseOrderNotFoundException::new);
        if (order.status() != DRAFT && !PurchaseOrderActors.isAdmin(actor)) {
            throw new PurchaseOrderActorNotAuthorizedException();
        }
        order.transitionTo(CANCELLED);
        orders.save(order);
    }
}
