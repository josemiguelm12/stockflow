package com.stockflow.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Consulta de solo lectura del outbox para administración (RF-NOT-11). Nunca expone el payload. */
public interface OutboundEmailQueries {

    record OutboundEmailView(UUID id, String recipient, String templateKey, String state, int attemptCount,
                             Instant enqueuedAt, Instant lastAttemptAt, Instant nextAttemptAt, Instant sentAt,
                             Instant failedAt, String lastError) {
    }

    /** {@code state} nulo cuenta todos. */
    long count(String state);

    /** Más recientes primero (enqueued_at DESC, id). */
    List<OutboundEmailView> page(String state, int limit, long offset);
}
