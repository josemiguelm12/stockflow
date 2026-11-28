package com.stockflow.notification.application;

import com.stockflow.notification.domain.Notification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** RF-NOT-05: marca como leída una notificación propia; idempotente, conserva el {@code readAt} original. */
@Service
public class MarkNotificationRead {

    private final NotificationRepository notifications;
    private final Clock clock;

    MarkNotificationRead(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public Notification markRead(UUID actorId, UUID notificationId) {
        return notifications.markRead(actorId, notificationId, clock.instant())
                .orElseThrow(NotificationNotFoundException::new);
    }
}
