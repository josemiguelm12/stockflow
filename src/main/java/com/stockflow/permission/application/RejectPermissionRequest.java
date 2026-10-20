package com.stockflow.permission.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.permission.domain.PermissionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** RF-GP-04: un ADMIN activo rechaza una solicitud ajena con un motivo escrito, que el agregado exige. */
@Service
public class RejectPermissionRequest {

    private static final String ADMIN = "ADMIN";
    private static final String ACTIVE = "ACTIVE";

    private final UserAdministrationRepository users;
    private final PermissionRequestRepository requests;
    private final Clock clock;

    RejectPermissionRequest(UserAdministrationRepository users, PermissionRequestRepository requests, Clock clock) {
        this.users = users;
        this.requests = requests;
        this.clock = clock;
    }

    @Transactional
    public void reject(UUID actorId, UUID requestId, String reason) {
        boolean admin = users.find(actorId)
                .filter(actor -> ADMIN.equals(actor.role()) && ACTIVE.equals(actor.accountStatus()))
                .isPresent();
        if (!admin) {
            throw new PermissionActorNotAuthorizedException();
        }
        PermissionRequest request = requests.lockById(requestId).orElseThrow(PermissionRequestNotFoundException::new);
        if (request.requestedBy().equals(actorId)) {
            throw new PermissionSelfResolutionException();
        }
        request.reject(actorId, clock.instant(), reason);
        requests.save(request);
    }
}
