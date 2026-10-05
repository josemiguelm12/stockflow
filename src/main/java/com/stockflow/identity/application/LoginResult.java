package com.stockflow.identity.application;

/** Resultado del login. Los rechazos son valores (no excepciones) para que el contador de fallos se confirme. */
public sealed interface LoginResult {

    record Authenticated(String accessToken, long expiresInSeconds) implements LoginResult {

        @Override
        public String toString() {
            return "Authenticated[redacted]";
        }
    }

    /** Usuario desconocido, contraseña incorrecta o cuenta bloqueada: indistinguibles para el cliente. */
    record InvalidCredentials() implements LoginResult {
    }

    /** Credenciales correctas de una cuenta que no está ACTIVE. */
    record AccountNotActive() implements LoginResult {
    }
}
