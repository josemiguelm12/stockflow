package com.stockflow.identity.adapter.in.web;

import java.util.UUID;

record MeResponse(UUID id, String email, String role) {

    /** Spring registra la respuesta escrita en DEBUG: sin email. */
    @Override
    public String toString() {
        return "MeResponse[id=" + id + ", role=" + role + "]";
    }
}
