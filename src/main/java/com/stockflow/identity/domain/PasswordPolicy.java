package com.stockflow.identity.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;
    /** BCrypt solo procesa los primeros 72 bytes. */
    private static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    /** Devuelve el motivo del rechazo, o vacío si la contraseña cumple la política. */
    public static Optional<String> violation(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return Optional.of("must have at least 8 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of("must be at most 72 bytes long");
        }
        if (password.chars().noneMatch(Character::isLetter)) {
            return Optional.of("must contain at least one letter");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            return Optional.of("must contain at least one digit");
        }
        return Optional.empty();
    }
}
