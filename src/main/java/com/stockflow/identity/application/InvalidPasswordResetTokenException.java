package com.stockflow.identity.application;

/** Token desconocido, vencido, usado, invalidado, de otro propósito o de un usuario no activo: indistinguibles. */
public class InvalidPasswordResetTokenException extends RuntimeException {

    public InvalidPasswordResetTokenException() {
        super("Password reset token is invalid, expired or already used");
    }
}
