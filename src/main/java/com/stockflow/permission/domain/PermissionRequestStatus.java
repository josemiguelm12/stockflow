package com.stockflow.permission.domain;

/**
 * Estados de una solicitud de permiso. Las transiciones entre ellos viven solo en
 * {@link PermissionRequestTransitionPolicy}. {@code APPROVED} no equivale a permiso activo; solo {@code APPLIED}.
 */
public enum PermissionRequestStatus {
    PENDING,
    APPROVED,
    APPLIED,
    REJECTED
}
