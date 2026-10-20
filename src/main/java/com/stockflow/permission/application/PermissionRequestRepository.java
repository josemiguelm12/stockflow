package com.stockflow.permission.application;

import com.stockflow.permission.domain.PermissionRequest;

import java.util.List;
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

    /**
     * Bloquea (FOR UPDATE) la fila de la solicitud hasta el fin de la transacción, que debe existir: serializa las
     * decisiones sobre una misma solicitud. Mismo mapeo que {@link #findById}.
     */
    Optional<PermissionRequest> lockById(UUID id);

    /** Solo {@code PENDING}, ordenadas por requested_at ASC, id ASC. */
    List<PermissionRequest> findPending();

    /** Solo las {@code PENDING} de ese solicitante, ordenadas por requested_at ASC, id ASC. */
    List<PermissionRequest> findPendingByRequester(UUID requesterId);
}
