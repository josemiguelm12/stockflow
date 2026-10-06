package com.stockflow.identity.application;

import com.stockflow.identity.domain.EmailNormalizer;
import com.stockflow.identity.domain.PasswordPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class RegisterUser {

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final ActivationIssuer issuer;
    private final Clock clock;

    RegisterUser(UserRepository users, PasswordHasher hasher, ActivationIssuer issuer, Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.issuer = issuer;
        this.clock = clock;
    }

    public record Registered(UUID id, String email) {

        @Override
        public String toString() {
            return "Registered[id=" + id + "]";
        }
    }

    @Transactional
    public Registered register(String email, String password) {
        String normalized = EmailNormalizer.normalize(email);
        if (!EmailNormalizer.isValid(normalized)) {
            throw new InvalidInputException("email", "must be a valid email address");
        }
        PasswordPolicy.violation(password).ifPresent(reason -> {
            throw new InvalidInputException("password", reason);
        });

        UUID id = UUID.randomUUID();
        var now = clock.instant();
        users.insertPending(id, normalized, hasher.hash(password), now);
        issuer.issue(id, normalized, now);
        return new Registered(id, normalized);
    }
}
