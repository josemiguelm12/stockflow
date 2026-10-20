package com.stockflow.permission.domain;

/**
 * Catálogo tipado de permisos de negocio. Es independiente de los roles {@code ADMIN}/{@code STANDARD} de
 * {@code identity}. Declarar un código no concede acceso a ninguna operación: su evaluación es posterior.
 */
public enum PermissionCode {
    PURCHASE_ORDER_CREATE
}
