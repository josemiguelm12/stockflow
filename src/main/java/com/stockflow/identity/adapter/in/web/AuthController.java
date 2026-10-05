package com.stockflow.identity.adapter.in.web;

import com.stockflow.identity.application.ActivateAccount;
import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.identity.application.Login;
import com.stockflow.identity.application.LoginResult;
import com.stockflow.identity.application.Logout;
import com.stockflow.identity.application.RegisterUser;
import com.stockflow.identity.application.ResendActivation;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Solo en la aplicación web: el worker SMTP arranca sin servidor y sin los casos de uso de sesión. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/auth")
class AuthController {

    private final RegisterUser registerUser;
    private final ActivateAccount activateAccount;
    private final ResendActivation resendActivation;
    private final Login login;
    private final Logout logout;

    AuthController(RegisterUser registerUser, ActivateAccount activateAccount, ResendActivation resendActivation,
                   Login login, Logout logout) {
        this.registerUser = registerUser;
        this.activateAccount = activateAccount;
        this.resendActivation = resendActivation;
        this.login = login;
        this.logout = logout;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        var registered = registerUser.register(request.email(), request.password());
        return new RegisterResponse(registered.id(), registered.email(), "PENDING_ACTIVATION");
    }

    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void activate(@Valid @RequestBody ActivateRequest request) {
        activateAccount.activate(request.token());
    }

    @PostMapping("/resend-activation")
    ResponseEntity<ResendResponse> resendActivation(@Valid @RequestBody ResendRequest request) {
        resendActivation.resend(request.email());
        return ResponseEntity.accepted().body(ResendResponse.UNIFORM);
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        return switch (login.login(request.email(), request.password())) {
            case LoginResult.Authenticated ok ->
                    ResponseEntity.ok(new LoginResponse(ok.accessToken(), "Bearer", ok.expiresInSeconds()));
            // Mismo status y cuerpo para usuario desconocido, contraseña incorrecta y cuenta bloqueada.
            case LoginResult.InvalidCredentials ignored -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid credentials."));
            case LoginResult.AccountNotActive ignored -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "The account is not active."));
        };
    }

    @GetMapping("/me")
    MeResponse me(Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        return new MeResponse(user.userId(), user.email(), user.role());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(Authentication authentication) {
        logout.logout(((AuthenticatedUser) authentication.getPrincipal()).sessionId());
    }
}
