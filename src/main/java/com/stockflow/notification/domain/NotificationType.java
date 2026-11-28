package com.stockflow.notification.domain;

/** Tipos de aviso funcional; el nombre también es la clave de plantilla del correo funcional. */
public enum NotificationType {
    PERMISSION_REQUEST_CREATED,
    PERMISSION_REQUEST_APPROVED,
    PERMISSION_REQUEST_REJECTED,
    PURCHASE_ORDER_APPROVED
}
