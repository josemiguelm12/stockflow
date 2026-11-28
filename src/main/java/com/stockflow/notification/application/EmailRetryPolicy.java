package com.stockflow.notification.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * RF-NOT-10: tres intentos totales. Tras el primer fallo se reintenta pasado 1 minuto; tras el segundo, pasados 5
 * minutos más; el tercer fallo es terminal (FAILED).
 */
public final class EmailRetryPolicy {

    public static final int MAX_ATTEMPTS = 3;
    private static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));

    private EmailRetryPolicy() {
    }

    /** Espera antes del siguiente intento, o vacío si {@code attemptsMade} agota los intentos. */
    public static Optional<Duration> delayAfterFailure(int attemptsMade) {
        if (attemptsMade < 1 || attemptsMade >= MAX_ATTEMPTS) {
            return Optional.empty();
        }
        return Optional.of(DELAYS.get(attemptsMade - 1));
    }
}
