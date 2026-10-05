package com.stockflow.identity.application;

/** Encola (sin enviar) el correo de activación dentro de la transacción en curso. */
public interface ActivationEmailPort {

    void queue(String recipientEmail, String rawToken);
}
