package com.stockflow.notification.adapter.in.web;

import com.stockflow.notification.application.OutboundEmailQueries.OutboundEmailView;

import java.time.Instant;
import java.util.UUID;

/** Metadatos de una entrega: sin payload, token ni credenciales. {@code lastError} ya llega sanitizado. */
record OutboundEmailResponse(UUID id, String recipient, String templateKey, String state, int attemptCount,
                             Instant enqueuedAt, Instant lastAttemptAt, Instant nextAttemptAt, Instant sentAt,
                             Instant failedAt, String lastError) {

    static OutboundEmailResponse from(OutboundEmailView v) {
        return new OutboundEmailResponse(v.id(), v.recipient(), v.templateKey(), v.state(), v.attemptCount(),
                v.enqueuedAt(), v.lastAttemptAt(), v.nextAttemptAt(), v.sentAt(), v.failedAt(), v.lastError());
    }

    @Override
    public String toString() {
        return "OutboundEmailResponse[id=" + id + ", state=" + state + ", attemptCount=" + attemptCount + "]";
    }
}
