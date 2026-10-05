package com.stockflow.notification.application;

/** Datos mínimos para construir el enlace de activación; solo existe cifrado en la base de datos. */
record ActivationPayload(String token) {

    @Override
    public String toString() {
        return "ActivationPayload[redacted]";
    }
}
