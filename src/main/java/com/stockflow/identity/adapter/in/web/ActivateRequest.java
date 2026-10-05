package com.stockflow.identity.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record ActivateRequest(@NotBlank @Size(max = 512) String token) {

    @Override
    public String toString() {
        return "ActivateRequest[redacted]";
    }
}
