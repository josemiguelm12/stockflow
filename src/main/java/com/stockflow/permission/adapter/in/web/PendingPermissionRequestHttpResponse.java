package com.stockflow.permission.adapter.in.web;

import com.stockflow.permission.application.ListPendingPermissionRequests;

import java.time.Instant;
import java.util.UUID;

/** Solicitud pendiente: sin motivo ni metadatos de decisión. */
record PendingPermissionRequestHttpResponse(UUID id, UUID requestedBy, String permissionCode, Instant requestedAt) {

    static PendingPermissionRequestHttpResponse from(ListPendingPermissionRequests.Item item) {
        return new PendingPermissionRequestHttpResponse(item.id(), item.requestedBy(), item.permissionCode().name(),
                item.requestedAt());
    }
}
