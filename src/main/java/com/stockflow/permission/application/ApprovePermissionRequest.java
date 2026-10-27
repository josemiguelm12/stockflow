package com.stockflow.permission.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.permission.domain.PermissionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * RF-GP-03 y RF-GP-05: un ADMIN activo aprueba una solicitud ajena y la concesión se aplica en la misma transacción.
 * La solicitud termina en {@code APPLIED} con exactamente una concesión, o nada cambia.
 */
@Service
public class ApprovePermissionRequest {

    private static final String ADMIN = "ADMIN";
    private static final String ACTIVE = "ACTIVE";

    private final UserAdministrationRepository users;
    private final PermissionRequestRepository requests;
    private final UserPermissionRepository grants;
    private final Clock clock;

    ApprovePermissionRequest(UserAdministrationRepository users, PermissionRequestRepository requests,
                             UserPermissionRepository grants, Clock clock) {
        this.users = users;
        this.requests = requests;
        this.grants = grants;
        this.clock = clock;
    }

    /**
     * El bloqueo de fila serializa decisiones concurrentes: quien espera relee la solicitud ya resuelta y la política
     * rechaza la segunda aprobación sin conceder otra fila. Si la concesión o el guardado fallan, todo se revierte.
     */
    @Transactional
    public void approve(UUID actorId, UUID requestId) {
        boolean admin = users.find(actorId)
                .filter(actor -> ADMIN.equals(actor.role()) && ACTIVE.equals(actor.accountStatus()))
                .isPresent();
        if (!admin) {
            throw new PermissionActorNotAuthorizedException();
        }
        PermissionRequest request = requests.lockById(requestId).orElseThrow(PermissionRequestNotFoundException::new);
        if (!request.requestedBy().equals(actorId)) {
            throw new PermissionSelfResolutionException();
        }
        Instant now = clock.instant();
        request.approve(actorId, now);
        grants.save(new GrantedPermission(UUID.randomUUID(), request.requestedBy(), request.permissionCode(),
                request.id(), now));
        request.apply(actorId, now);
        requests.save(request);
    }
}
