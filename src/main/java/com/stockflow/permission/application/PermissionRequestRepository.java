package com.stockflow.permission.application;

import com.stockflow.permission.domain.PermissionRequest;

import java.util.Optional;
import java.util.UUID;

public interface PermissionRequestRepository {

    /**
     * Inserta la solicitud o, si ya existe, persiste su estado y metadatos de ciclo de vida; solicitante, permiso y
     * fecha de solicitud no cambian.
     */
    void save(PermissionRequest request);

    /** Reconstruye la solicitud con {@link PermissionRequest#restore}, que rechaza filas incoherentes. */
    Optional<PermissionRequest> findById(UUID id);
}
