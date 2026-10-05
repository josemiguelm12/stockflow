package com.stockflow.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sin userId: el usuario es siempre el del Bearer. */
record ChangePasswordRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @NotBlank @Size(max = 200) String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[redacted]";
    }
}
