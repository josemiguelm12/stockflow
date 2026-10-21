package com.stockflow.permission.adapter.in.web;

import com.stockflow.permission.domain.PermissionCode;
import jakarta.validation.constraints.NotNull;

/** Solo el permiso pedido; solicitante, estado e instantes los decide el servidor. */
record CreatePermissionRequestHttpRequest(@NotNull PermissionCode permissionCode) {
}
