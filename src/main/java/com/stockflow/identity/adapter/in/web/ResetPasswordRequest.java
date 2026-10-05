package com.stockflow.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ResetPasswordRequest(
        @NotBlank @Size(max = 512) String token,
        @NotBlank @Size(max = 200) String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[redacted]";
    }
}
