package com.stockflow.identity.adapter.out.security;

import com.stockflow.identity.application.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** BCrypt genera una sal aleatoria distinta por cada hash. */
@Component
class BcryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String hash) {
        try {
            return rawPassword != null && hash != null && encoder.matches(rawPassword, hash);
        } catch (IllegalArgumentException e) {
            // Contraseña de más de 72 bytes u otra entrada que BCrypt rechaza: no coincide.
            return false;
        }
    }
}
