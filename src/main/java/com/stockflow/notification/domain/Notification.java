package com.stockflow.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Aviso interno para un usuario. Solo cambia al leerse: {@code readAt} se fija una vez. */
public final class Notification {

    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_MESSAGE_LENGTH = 2000;
    private static final String ELLIPSIS = "…";

    private final UUID id;
    private final UUID userId;
    private final NotificationType type;
    private final String title;
    private final String message;
    private final NotificationResourceType resourceType;
    private final UUID resourceId;
    private final Instant createdAt;
    private Instant readAt;

    private Notification(UUID id, UUID userId, NotificationType type, String title, String message,
                         NotificationResourceType resourceType, UUID resourceId, Instant createdAt, Instant readAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.type = Objects.requireNonNull(type, "type");
        this.title = requireText(title, "title", MAX_TITLE_LENGTH);
        this.message = requireText(message, "message", MAX_MESSAGE_LENGTH);
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.readAt = readAt;
    }

    /** Nueva notificación no leída. */
    public static Notification create(UUID id, UUID userId, NotificationType type, String title, String message,
                                      NotificationResourceType resourceType, UUID resourceId, Instant createdAt) {
        return new Notification(id, userId, type, title, message, resourceType, resourceId, createdAt, null);
    }

    public static Notification restore(UUID id, UUID userId, NotificationType type, String title, String message,
                                       NotificationResourceType resourceType, UUID resourceId, Instant createdAt,
                                       Instant readAt) {
        return new Notification(id, userId, type, title, message, resourceType, resourceId, createdAt, readAt);
    }

    /** Recorta un texto libre (p. ej. el motivo de un rechazo) para que quepa en un mensaje válido. */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - ELLIPSIS.length()) + ELLIPSIS;
    }

    /** Idempotente: una notificación ya leída conserva su {@code readAt} original. */
    public void markRead(Instant now) {
        Objects.requireNonNull(now, "now");
        if (readAt == null) {
            readAt = now.isBefore(createdAt) ? createdAt : now;
        }
    }

    public boolean isRead() {
        return readAt != null;
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidNotificationException(field + " is required");
        }
        if (value.length() > max) {
            throw new InvalidNotificationException(field + " is too long");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public NotificationType type() {
        return type;
    }

    public String title() {
        return title;
    }

    public String message() {
        return message;
    }

    public NotificationResourceType resourceType() {
        return resourceType;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> readAt() {
        return Optional.ofNullable(readAt);
    }

    @Override
    public String toString() {
        return "Notification[id=" + id + ", type=" + type + ", read=" + isRead() + "]";
    }
}
