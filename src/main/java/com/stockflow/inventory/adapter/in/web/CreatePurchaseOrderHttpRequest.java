package com.stockflow.inventory.adapter.in.web;

import java.util.UUID;

/** {@code supplierId} es opcional; el cuerpo no autoriza nada: decide la concesión persistida. */
record CreatePurchaseOrderHttpRequest(UUID supplierId) {
}
