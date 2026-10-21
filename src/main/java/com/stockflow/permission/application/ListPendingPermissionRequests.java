package com.stockflow.permission.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.permission.domain.PermissionCode;
import com.stockflow.permission.domain.PermissionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RF-GP-02: solicitudes pendientes. Un ADMIN activo ve todas; un STANDARD activo solo las suyas. El alcance se deduce
 * del rol persistido del actor, nunca de un solicitante o rol recibido.
 */
@Service
public class ListPendingPermissionRequests {

    private static final String ADMIN = "ADMIN";
    private static final String STANDARD = "STANDARD";
    private static final String ACTIVE = "ACTIVE";

    /** Sin motivo ni metadatos de decisión: una pendiente no los tiene. */
    public record Item(UUID id, UUID requestedBy, PermissionCode permissionCode, Instant requestedAt) {
    }

    private final UserAdministrationRepository users;
    private final PermissionRequestRepository requests;

    ListPendingPermissionRequests(UserAdministrationRepository users, PermissionRequestRepository requests) {
        this.users = users;
        this.requests = requests;
    }

    @Transactional(readOnly = true)
    public List<Item> list(UUID actorId) {
        String role = users.find(actorId)
                .filter(actor -> ACTIVE.equals(actor.accountStatus()))
                .map(UserAdministrationRepository.ManagedUser::role)
                .orElseThrow(PermissionActorNotAuthorizedException::new);
        List<PermissionRequest> pending;
        if (ADMIN.equals(role)) {
            pending = requests.findPending();
        } else if (STANDARD.equals(role)) {
            pending = requests.findPendingByRequester(actorId);
        } else {
            throw new PermissionActorNotAuthorizedException();
        }
        return pending.stream()
                .map(r -> new Item(r.id(), r.requestedBy(), r.permissionCode(), r.requestedAt()))
                .toList();
    }
}
