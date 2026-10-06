package com.stockflow.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ForgotPasswordRequest(@NotBlank @Size(max = 400) String email) {

    /** Spring registra el cuerpo leído en DEBUG: nunca el email. */
    @Override
    public String toString() {
        return "ForgotPasswordRequest[redacted]";
    }
}
