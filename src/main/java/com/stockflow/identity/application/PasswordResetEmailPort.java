package com.stockflow.identity.application;

/** Encola (sin enviar) el correo de recuperación dentro de la transacción en curso. */
public interface PasswordResetEmailPort {

    void queue(String recipientEmail, String rawToken);
}
