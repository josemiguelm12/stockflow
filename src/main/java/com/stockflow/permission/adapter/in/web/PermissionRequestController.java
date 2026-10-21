package com.stockflow.permission.adapter.in.web;

import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.permission.application.ApprovePermissionRequest;
import com.stockflow.permission.application.CreatePermissionRequest;
import com.stockflow.permission.application.ListPendingPermissionRequests;
import com.stockflow.permission.application.RejectPermissionRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Gestión de Permisos (RF-GP-01…07). SecurityConfig exige Bearer en todas las rutas y rol ADMIN para aprobar o
 * rechazar; cada caso de uso revalida actor, rol y estado persistidos. El actor sale siempre del Bearer: ningún
 * cuerpo aporta solicitante, rol, decisor, estado ni instantes.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/permission-requests")
class PermissionRequestController {

    private final CreatePermissionRequest createPermissionRequest;
    private final ListPendingPermissionRequests listPendingPermissionRequests;
    private final ApprovePermissionRequest approvePermissionRequest;
    private final RejectPermissionRequest rejectPermissionRequest;

    PermissionRequestController(CreatePermissionRequest createPermissionRequest,
                                ListPendingPermissionRequests listPendingPermissionRequests,
                                ApprovePermissionRequest approvePermissionRequest,
                                RejectPermissionRequest rejectPermissionRequest) {
        this.createPermissionRequest = createPermissionRequest;
        this.listPendingPermissionRequests = listPendingPermissionRequests;
        this.approvePermissionRequest = approvePermissionRequest;
        this.rejectPermissionRequest = rejectPermissionRequest;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatePermissionRequestHttpResponse create(Authentication authentication,
                                               @Valid @RequestBody CreatePermissionRequestHttpRequest request) {
        CreatePermissionRequest.Result result = createPermissionRequest.create(actor(authentication),
                new CreatePermissionRequest.Command(request.permissionCode()));
        return new CreatePermissionRequestHttpResponse(result.id(), result.status().name());
    }

    /** Una sola ruta: el caso de uso devuelve todas a un ADMIN y solo las propias a un STANDARD. */
    @GetMapping("/pending")
    List<PendingPermissionRequestHttpResponse> pending(Authentication authentication) {
        return listPendingPermissionRequests.list(actor(authentication)).stream()
                .map(PendingPermissionRequestHttpResponse::from)
                .toList();
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void approve(Authentication authentication, @PathVariable UUID id) {
        approvePermissionRequest.approve(actor(authentication), id);
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(Authentication authentication, @PathVariable UUID id,
                @Valid @RequestBody RejectPermissionRequestHttpRequest request) {
        rejectPermissionRequest.reject(actor(authentication), id, request.reason());
    }

    private static UUID actor(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }
}
