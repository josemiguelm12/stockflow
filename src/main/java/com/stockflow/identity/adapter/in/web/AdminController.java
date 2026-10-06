package com.stockflow.identity.adapter.in.web;

import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.ChangeUserRole;
import com.stockflow.identity.application.ChangeUserStatus;
import com.stockflow.identity.application.ForcePasswordReset;
import com.stockflow.identity.application.InvalidInputException;
import com.stockflow.identity.application.ListUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Administración de usuarios. La autorización por rol ADMIN se declara en SecurityConfig (401 sin Bearer válido,
 * 403 para STANDARD) y cada caso de uso revalida al ADMIN dentro de su transacción. El usuario que actúa sale
 * siempre del Bearer; los cuerpos son cerrados (cualquier propiedad extra es un 400).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/admin/users")
class AdminController {

    private final ListUsers listUsers;
    private final ChangeUserRole changeUserRole;
    private final ChangeUserStatus changeUserStatus;
    private final ForcePasswordReset forcePasswordReset;

    AdminController(ListUsers listUsers, ChangeUserRole changeUserRole, ChangeUserStatus changeUserStatus,
                    ForcePasswordReset forcePasswordReset) {
        this.listUsers = listUsers;
        this.changeUserRole = changeUserRole;
        this.changeUserStatus = changeUserStatus;
        this.forcePasswordReset = forcePasswordReset;
    }

    @GetMapping
    AdminUserPageResponse list(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return AdminUserPageResponse.from(listUsers.list(page, size));
    }

    @PatchMapping("/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeRole(Authentication authentication, @PathVariable UUID id,
                    @RequestBody(required = false) ClosedBody body) {
        changeUserRole.change(actor(authentication), id, onlyStringField(body, "role"));
    }

    @PatchMapping("/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeStatus(Authentication authentication, @PathVariable UUID id,
                      @RequestBody(required = false) ClosedBody body) {
        changeUserStatus.change(actor(authentication), id, onlyStringField(body, "accountStatus"));
    }

    /**
     * Sin cuerpo: cualquier contenido (incluido "{}" o campos como userId/email) es un 400 y no ejecuta nada. Se mira
     * el stream directamente en vez de convertirlo, para que ningún contenido enviado acabe en los logs de DEBUG.
     */
    @PostMapping("/{id}/force-password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void forcePasswordReset(Authentication authentication, @PathVariable UUID id, HttpServletRequest request)
            throws IOException {
        if (request.getInputStream().read() != -1) {
            throw new InvalidInputException("body", "this request must not have a body");
        }
        forcePasswordReset.force(actor(authentication), id);
    }

    private static UUID actor(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }

    /** Cuerpo cerrado: exactamente un campo, de tipo texto. Nunca se acepta userId, email, hash ni otro campo. */
    private static String onlyStringField(ClosedBody body, String field) {
        Map<String, Object> fields = body == null ? Map.of() : body.fields();
        if (fields.size() != 1 || !fields.containsKey(field)) {
            throw new InvalidInputException(field, "the request body must contain only '" + field + "'");
        }
        if (!(fields.get(field) instanceof String value)) {
            throw new InvalidInputException(field, "must be a string");
        }
        return value;
    }
}
