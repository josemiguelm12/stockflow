package com.stockflow.inventory.adapter.in.web;

import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.inventory.application.CreatePurchaseOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Creación mínima de órdenes de compra para demostrar RF-GP-05. La ruta solo exige Bearer: decide la concesión
 * persistida de {@code PURCHASE_ORDER_CREATE} que consulta el caso de uso, no el rol.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/purchase-orders")
class PurchaseOrderController {

    private final CreatePurchaseOrder createPurchaseOrder;

    PurchaseOrderController(CreatePurchaseOrder createPurchaseOrder) {
        this.createPurchaseOrder = createPurchaseOrder;
    }

    /** El cuerpo es opcional: sin cuerpo o sin {@code supplierId}, la orden se crea sin proveedor. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatePurchaseOrderHttpResponse create(Authentication authentication,
                                           @RequestBody(required = false) CreatePurchaseOrderHttpRequest request) {
        UUID supplierId = request == null ? null : request.supplierId();
        UUID actorId = ((AuthenticatedUser) authentication.getPrincipal()).userId();
        return new CreatePurchaseOrderHttpResponse(createPurchaseOrder.create(actorId, supplierId));
    }
}
