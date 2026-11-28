package com.stockflow.notification.application;

import com.stockflow.notification.domain.Notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {

    /** Inserta salvo que ya exista (user_id, event_key). Devuelve true solo si insertó una fila. */
    boolean insertIfAbsent(Notification notification, String eventKey);

    /** Solo del usuario; {@code read} nulo = leídas y no leídas. Orden created_at DESC, id DESC. */
    List<Notification> findByUser(UUID userId, Boolean read, int size, long offset);

    long countByUser(UUID userId, Boolean read);

    /**
     * Fija read_at una sola vez y devuelve la notificación; vacío si no existe o no es del usuario (el filtro por
     * usuario va en el propio UPDATE).
     */
    Optional<Notification> markRead(UUID userId, UUID notificationId, Instant now);
}
