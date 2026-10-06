package com.stockflow.identity.application;

import com.stockflow.identity.domain.EmailNormalizer;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Inicio de sesión. La fila del usuario se bloquea durante el intento, de modo que los intentos simultáneos
 * contra una misma cuenta se evalúan uno tras otro y el contador de fallos no pierde incrementos.
 * Los rechazos se devuelven como valor (no se lanzan) para que el contador y el bloqueo se confirmen.
 */
public class Login {

    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final LoginResult INVALID = new LoginResult.InvalidCredentials();
    private static final LoginResult NOT_ACTIVE = new LoginResult.AccountNotActive();
    private static final LoginResult RESET_REQUIRED = new LoginResult.PasswordResetRequired();

    private final UserRepository users;
    private final SessionRepository sessions;
    private final AccessTokenIssuer issuer;
    private final PasswordHasher hasher;
    private final Clock clock;
    /** Hash descartable: comparar contra él iguala el coste de BCrypt cuando no hay cuenta evaluable. */
    private final String dummyHash;

    public Login(UserRepository users, SessionRepository sessions, AccessTokenIssuer issuer,
                 PasswordHasher hasher, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.issuer = issuer;
        this.hasher = hasher;
        this.clock = clock;
        this.dummyHash = hasher.hash(UUID.randomUUID().toString());
    }

    @Transactional
    public LoginResult login(String email, String password) {
        String normalized = EmailNormalizer.normalize(email);
        // Un email mal formado se rechaza antes de tocar la base de datos. El formato no depende de si la cuenta
        // existe, así que no permite enumerar usuarios; un email válido desconocido sigue recibiendo el 401 genérico.
        if (!EmailNormalizer.isValid(normalized)) {
            throw new InvalidInputException("email", "must be a valid email address");
        }
        Instant now = clock.instant();

        Optional<LoginAccount> found = users.lockForLogin(normalized);
        if (found.isEmpty()) {
            hasher.matches(password, dummyHash);
            return INVALID;
        }
        LoginAccount account = found.get();

        if (account.isLockedAt(now)) {
            // Bloqueo vigente: se rechaza también la contraseña correcta, sin tocar el contador ni crear sesión.
            hasher.matches(password, dummyHash);
            return INVALID;
        }

        if (!hasher.matches(password, account.passwordHash())) {
            int attempts = account.failedAttempts() + 1;
            Instant lockedUntil = attempts >= MAX_FAILED_ATTEMPTS ? now.plus(LOCK_DURATION) : account.lockedUntil();
            users.recordFailedLogin(account.id(), attempts, lockedUntil, now);
            return INVALID;
        }

        if (!"ACTIVE".equals(account.accountStatus())) {
            return NOT_ACTIVE;
        }
        if (account.passwordResetRequired()) {
            // Reset forzado por un ADMIN: la contraseña anterior ya no crea sesión. No se limpian fallos ni bloqueo.
            return RESET_REQUIRED;
        }

        if (account.failedAttempts() != 0 || account.lockedUntil() != null) {
            users.clearLoginFailures(account.id(), now);
        }
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = now.plus(ACCESS_TOKEN_TTL);
        sessions.insert(sessionId, account.id(), now, expiresAt);
        String token = issuer.issue(account.id(), sessionId, now, expiresAt);
        return new LoginResult.Authenticated(token, ACCESS_TOKEN_TTL.toSeconds());
    }
}
