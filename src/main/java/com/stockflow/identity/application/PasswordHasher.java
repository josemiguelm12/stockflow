package com.stockflow.identity.application;

public interface PasswordHasher {

    String hash(String rawPassword);

    /** false ante contraseñas o hashes inválidos; nunca lanza por entrada del cliente. */
    boolean matches(String rawPassword, String hash);
}
