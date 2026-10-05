package com.stockflow.notification.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface OutboundEmailRepository {

    record PendingEmail(UUID id, String recipient, String templateKey, EncryptedPayload payload) {
    }

    void insertPending(UUID id, String recipient, String templateKey, EncryptedPayload payload, Instant enqueuedAt);

    /** Reclama el siguiente PENDING con FOR UPDATE SKIP LOCKED; requiere una transacción activa. */
    Optional<PendingEmail> claimNextPending(Collection<UUID> excludedIds);

    /** Marca SENT, asigna sent_at y purga ciphertext, IV y key id. */
    void markSent(UUID id, Instant sentAt);
}
