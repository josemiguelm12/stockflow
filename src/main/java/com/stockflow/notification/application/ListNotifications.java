package com.stockflow.notification.application;

import com.stockflow.identity.application.InvalidInputException;
import com.stockflow.notification.domain.Notification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** RF-NOT-04: solo las notificaciones del actor autenticado. */
@Service
public class ListNotifications {

    static final int MAX_PAGE_SIZE = 100;

    public record NotificationPage(List<Notification> items, int page, int size, long totalElements, long totalPages) {
    }

    private final NotificationRepository notifications;

    ListNotifications(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** {@code read} nulo devuelve ambas. REPEATABLE READ: total y página salen de la misma instantánea. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationPage list(UUID actorId, Boolean read, int page, int size) {
        if (page < 0) {
            throw new InvalidInputException("page", "must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidInputException("size", "must be between 1 and " + MAX_PAGE_SIZE);
        }
        long total = notifications.countByUser(actorId, read);
        List<Notification> items = notifications.findByUser(actorId, read, size, (long) page * size);
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return new NotificationPage(items, page, size, total, totalPages);
    }
}
