package com.stockflow.identity.adapter.in.web;

import com.stockflow.identity.application.ActivateAccount;
import com.stockflow.identity.application.RegisterUser;
import com.stockflow.identity.application.ResendActivation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final RegisterUser registerUser;
    private final ActivateAccount activateAccount;
    private final ResendActivation resendActivation;

    AuthController(RegisterUser registerUser, ActivateAccount activateAccount, ResendActivation resendActivation) {
        this.registerUser = registerUser;
        this.activateAccount = activateAccount;
        this.resendActivation = resendActivation;
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
}
