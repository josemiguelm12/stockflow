package com.stockflow.notification.application;

/** Contenido del correo funcional; solo existe cifrado en el outbox. */
record FunctionalEmailPayload(String title, String message) {

    @Override
    public String toString() {
        return "FunctionalEmailPayload[redacted]";
    }
}
