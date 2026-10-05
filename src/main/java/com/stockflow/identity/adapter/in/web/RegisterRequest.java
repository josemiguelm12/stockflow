package com.stockflow.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Solo email y password: rol, estado o hash no se pueden enviar desde el cliente. */
record RegisterRequest(
        @NotBlank @Size(max = 400) String email,
        @NotBlank @Size(max = 200) String password) {

    @Override
    public String toString() {
        return "RegisterRequest[redacted]";
    }
}
