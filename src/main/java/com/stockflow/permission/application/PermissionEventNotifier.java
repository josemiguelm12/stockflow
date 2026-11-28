package com.stockflow.permission.application;

import com.stockflow.permission.domain.PermissionRequest;

/**
 * Puerto de avisos de la gestión de permisos (P5). Se invoca dentro de la transacción del caso de uso, después de
 * guardar la solicitud; nunca contacta SMTP y cualquier fallo revierte la decisión.
 */
public interface PermissionEventNotifier {

    /** RF-NOT-01: avisa a los ADMIN activos de una solicitud nueva. */
    void requestCreated(PermissionRequest request);

    /** RF-NOT-02: avisa solo al solicitante de la aprobación. */
    void requestApproved(PermissionRequest request);

    /** RF-NOT-02: avisa solo al solicitante del rechazo, con el motivo literal. */
    void requestRejected(PermissionRequest request, String reason);
}
