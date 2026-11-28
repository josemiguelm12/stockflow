package com.stockflow.notification.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OutboundEmailRepository {

    record PendingEmail(UUID id, String recipient, String templateKey, EncryptedPayload payload, int attemptCount) {
    }

    void insertPending(UUID id, String recipient, String templateKey, EncryptedPayload payload, Instant enqueuedAt);

    /**
     * Como {@link #insertPending} pero idempotente por {@code dedupeKey} (evento + destinatario + canal): devuelve
     * false, sin insertar, si ya existe una fila con esa clave (en cualquier estado).
     */
    boolean insertPendingIfAbsent(UUID id, String recipient, String templateKey, EncryptedPayload payload,
                                  Instant enqueuedAt, String dedupeKey);

    /**
     * Reclama el siguiente PENDING elegible (sin próximo intento o con {@code next_attempt_at <= now}) con
     * FOR UPDATE SKIP LOCKED; requiere una transacción activa.
     */
    Optional<PendingEmail> claimNextPending(Instant now);

    /** Marca SENT, cuenta el intento, asigna sent_at y purga ciphertext, IV y key id. */
    void markSent(UUID id, Instant sentAt);

    /** Registra un fallo con reintento: cuenta el intento, fija last_attempt_at, next_attempt_at y last_error. */
    void scheduleRetry(UUID id, int attemptCount, Instant attemptedAt, Instant nextAttemptAt, String error);

    /** Fallo terminal: FAILED, failed_at, último error, sin próximo intento; purga el payload cifrado. */
    void markFailed(UUID id, int attemptCount, Instant failedAt, String error);
}
