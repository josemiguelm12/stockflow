package com.stockflow.identity.application;

import java.time.Instant;
import java.util.UUID;

/** Fila de usuario bloqueada (FOR UPDATE) para evaluar un intento de login. */
public record LoginAccount(UUID id, String passwordHash, String accountStatus, int failedAttempts, Instant lockedUntil,
                           boolean passwordResetRequired) {

    boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    @Override
    public String toString() {
        return "LoginAccount[id=" + id + "]";
    }
}
