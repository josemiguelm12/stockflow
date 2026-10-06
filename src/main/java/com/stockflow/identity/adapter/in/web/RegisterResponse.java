package com.stockflow.identity.adapter.in.web;

import java.util.UUID;

record RegisterResponse(UUID id, String email, String accountStatus) {

    /** Spring registra la respuesta escrita en DEBUG: sin email. */
    @Override
    public String toString() {
        return "RegisterResponse[id=" + id + ", accountStatus=" + accountStatus + "]";
    }
}
