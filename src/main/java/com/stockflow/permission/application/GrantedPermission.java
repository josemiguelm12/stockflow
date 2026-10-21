package com.stockflow.permission.application;

import com.stockflow.permission.domain.PermissionCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Permiso concedido a un usuario a partir de la solicitud que lo originó. Registrarlo no evalúa ningún acceso. */
public record GrantedPermission(UUID id, UUID userId, PermissionCode permissionCode, UUID sourceRequestId,
                                Instant grantedAt) {

    public GrantedPermission {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(permissionCode, "permissionCode");
        Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        Objects.requireNonNull(grantedAt, "grantedAt");
    }
}
