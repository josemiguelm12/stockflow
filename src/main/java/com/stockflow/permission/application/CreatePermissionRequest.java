package com.stockflow.permission.application;

import com.stockflow.identity.application.UserAdministrationRepository;
import com.stockflow.permission.domain.InvalidPermissionRequestException;
import com.stockflow.permission.domain.PermissionCode;
import com.stockflow.permission.domain.PermissionRequest;
import com.stockflow.permission.domain.PermissionRequestStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** RF-GP-01: un usuario activo (STANDARD o ADMIN) solicita un permiso para sí mismo. */
@Service
public class CreatePermissionRequest {

    private static final String ACTIVE = "ACTIVE";

    /** Solo el permiso pedido: identificador, solicitante, estado e instante los fija el caso de uso. */
    public record Command(PermissionCode permissionCode) {
    }

    public record Result(UUID id, PermissionRequestStatus status) {
    }

    private final UserAdministrationRepository users;
    private final PermissionRequestRepository requests;
    private final Clock clock;

    CreatePermissionRequest(UserAdministrationRepository users, PermissionRequestRepository requests, Clock clock) {
        this.users = users;
        this.requests = requests;
        this.clock = clock;
    }

    @Transactional
    public Result create(UUID actorId, Command command) {
        if (command == null || command.permissionCode() == null) {
            throw new InvalidPermissionRequestException("permissionCode is required");
        }
        boolean active = users.find(actorId).filter(actor -> ACTIVE.equals(actor.accountStatus())).isPresent();
        if (!active) {
            throw new PermissionActorNotAuthorizedException();
        }
        PermissionRequest request = PermissionRequest.create(UUID.randomUUID(), actorId, command.permissionCode(),
                clock.instant());
        requests.save(request);
        return new Result(request.id(), request.status());
    }
}
