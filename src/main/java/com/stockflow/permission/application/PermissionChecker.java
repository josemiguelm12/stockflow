package com.stockflow.permission.application;

import com.stockflow.permission.domain.PermissionCode;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Evaluación de un permiso de negocio concreto (RF-GP-05). Solo consulta las concesiones persistidas: los roles
 * {@code ADMIN}/{@code STANDARD} no conceden ni sustituyen ningún permiso.
 */
@Service
public class PermissionChecker {

    private final UserPermissionRepository grants;

    public PermissionChecker(UserPermissionRepository grants) {
        this.grants = grants;
    }

    public boolean hasPermission(UUID actorId, PermissionCode code) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(code, "code");
        return grants.existsByUserIdAndPermissionCode(actorId, code);
    }
}
