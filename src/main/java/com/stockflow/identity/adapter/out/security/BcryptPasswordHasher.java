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
}
