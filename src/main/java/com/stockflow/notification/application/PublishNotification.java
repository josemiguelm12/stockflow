package com.stockflow.notification.application;

import com.stockflow.notification.domain.Notification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publica un evento funcional: notificación interna obligatoria y, si la preferencia lo permite, correo en el outbox.
 * Se une a la transacción del evento de negocio (obligatoria): cualquier fallo revierte evento, aviso y correo.
 */
@Service
public class PublishNotification {

    private final NotificationRepository notifications;
    private final FunctionalEmailOutbox emailOutbox;
    private final Clock clock;

    PublishNotification(NotificationRepository notifications, FunctionalEmailOutbox emailOutbox, Clock clock) {
        this.notifications = notifications;
        this.emailOutbox = emailOutbox;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Collection<NotificationRecipient> recipients, NotificationEvent event) {
        Map<UUID, NotificationRecipient> unique = new LinkedHashMap<>();
        for (NotificationRecipient recipient : recipients) {
            unique.putIfAbsent(recipient.userId(), recipient);
        }
        for (NotificationRecipient recipient : unique.values()) {
            Notification notification = Notification.create(UUID.randomUUID(), recipient.userId(), event.type(),
                    event.title(), event.message(), event.resourceType(), event.resourceId(), clock.instant());
            boolean inserted = notifications.insertIfAbsent(notification, event.eventKey());
            if (inserted && recipient.functionalEmailEnabled()) {
                emailOutbox.enqueue(recipient, event);
            }
        }
    }
}
