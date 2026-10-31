package com.stockflow.inventory.application;

import com.stockflow.inventory.domain.PurchaseOrder;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository {

    /** Inserta la orden o, si ya existe, persiste su estado actual; proveedor y fecha de creación no cambian. */
    void save(PurchaseOrder order);

    Optional<PurchaseOrder> findById(UUID id);

    /**
     * Bloquea (FOR UPDATE) la fila de la orden hasta el fin de la transacción, que debe existir: serializa las
     * transiciones sobre una misma orden. Mismo mapeo que {@link #findById}.
     */
    Optional<PurchaseOrder> lockById(UUID id);
}
