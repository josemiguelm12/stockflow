package com.stockflow.inventory.adapter.in.web;

import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.inventory.application.ApprovePurchaseOrder;
import com.stockflow.inventory.application.CancelPurchaseOrder;
import com.stockflow.inventory.application.ReceivePurchaseOrder;
import com.stockflow.inventory.application.SubmitPurchaseOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Transiciones de una orden de compra (P3-F1). SecurityConfig exige STANDARD o ADMIN para enviar y cancelar y ADMIN
 * para aprobar y recibir; cada caso de uso revalida actor, rol y estado persistidos. Las rutas no leen cuerpo: el
 * actor sale del Bearer y el estado destino lo fija la operación.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/purchase-orders/{id}")
class PurchaseOrderTransitionController {

    private final SubmitPurchaseOrder submitPurchaseOrder;
    private final ApprovePurchaseOrder approvePurchaseOrder;
    private final ReceivePurchaseOrder receivePurchaseOrder;
    private final CancelPurchaseOrder cancelPurchaseOrder;

    PurchaseOrderTransitionController(SubmitPurchaseOrder submitPurchaseOrder,
                                      ApprovePurchaseOrder approvePurchaseOrder,
                                      ReceivePurchaseOrder receivePurchaseOrder,
                                      CancelPurchaseOrder cancelPurchaseOrder) {
        this.submitPurchaseOrder = submitPurchaseOrder;
        this.approvePurchaseOrder = approvePurchaseOrder;
        this.receivePurchaseOrder = receivePurchaseOrder;
        this.cancelPurchaseOrder = cancelPurchaseOrder;
    }

    @PostMapping("/submit")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void submit(Authentication authentication, @PathVariable UUID id) {
        submitPurchaseOrder.submit(actor(authentication), id);
    }

    @PostMapping("/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(Authentication authentication, @PathVariable UUID id) {
        approvePurchaseOrder.approve(actor(authentication), id);
    }

    @PostMapping("/receive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void receive(Authentication authentication, @PathVariable UUID id) {
        receivePurchaseOrder.receive(actor(authentication), id);
    }

    @PostMapping("/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(Authentication authentication, @PathVariable UUID id) {
        cancelPurchaseOrder.cancel(actor(authentication), id);
    }

    private static UUID actor(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }
}
