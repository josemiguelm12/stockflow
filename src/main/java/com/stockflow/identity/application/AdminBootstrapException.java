package com.stockflow.identity.application;

/** Fallo seguro del bootstrap: el mensaje nunca incluye el email, la contraseña ni el hash. */
public class AdminBootstrapException extends RuntimeException {

    public AdminBootstrapException(String message) {
        super(message);
    }
}
