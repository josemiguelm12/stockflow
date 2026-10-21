package com.stockflow.permission.application;

import com.stockflow.permission.domain.PermissionCode;

import java.util.Optional;
import java.util.UUID;

public interface UserPermissionRepository {

    /** Inserta el permiso; un duplicado por solicitud origen o por (usuario, permiso) falla, no se ignora. */
    void save(GrantedPermission permission);

    Optional<GrantedPermission> findBySourceRequestId(UUID sourceRequestId);

    boolean existsByUserIdAndPermissionCode(UUID userId, PermissionCode permissionCode);
}
