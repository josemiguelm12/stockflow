package com.stockflow.identity.adapter.in.web;

import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.ChangePassword;
import com.stockflow.identity.application.RequestPasswordReset;
import com.stockflow.identity.application.ResetPassword;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Solo en la aplicación web. Ninguna operación crea sesión ni devuelve JWT. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/auth/password")
class PasswordController {

    private final RequestPasswordReset requestPasswordReset;
    private final ResetPassword resetPassword;
    private final ChangePassword changePassword;

    PasswordController(RequestPasswordReset requestPasswordReset, ResetPassword resetPassword,
                       ChangePassword changePassword) {
        this.requestPasswordReset = requestPasswordReset;
        this.resetPassword = resetPassword;
        this.changePassword = changePassword;
    }

    @PostMapping("/forgot")
    ResponseEntity<ForgotPasswordResponse> forgot(@Valid @RequestBody ForgotPasswordRequest request) {
        requestPasswordReset.request(request.email());
        return ResponseEntity.accepted().body(ForgotPasswordResponse.UNIFORM);
    }

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@Valid @RequestBody ResetPasswordRequest request) {
        resetPassword.reset(request.token(), request.newPassword());
    }

    @PostMapping("/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void change(Authentication authentication, @Valid @RequestBody ChangePasswordRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        changePassword.change(user.userId(), request.currentPassword(), request.newPassword());
    }
}
