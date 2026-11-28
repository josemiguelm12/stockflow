package com.stockflow.notification.adapter.in.web;

import com.stockflow.notification.domain.Notification;

import java.time.Instant;
import java.util.UUID;

/** Notificación propia tal como la ve su destinatario; sin userId ni clave de evento. */
record NotificationResponse(UUID id, String type, String title, String message, String resourceType,
                            UUID resourceId, boolean read, Instant readAt, Instant createdAt) {

    static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.id(), n.type().name(), n.title(), n.message(), n.resourceType().name(),
                n.resourceId(), n.isRead(), n.readAt().orElse(null), n.createdAt());
    }

    /** Spring puede registrar la respuesta en DEBUG: el mensaje puede contener un motivo de rechazo. */
    @Override
    public String toString() {
        return "NotificationResponse[id=" + id + ", type=" + type + ", read=" + read + "]";
    }
}
