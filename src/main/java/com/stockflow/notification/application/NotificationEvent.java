package com.stockflow.notification.application;

import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;

import java.util.Objects;
import java.util.UUID;

/**
 * Evento funcional notificable. La clave es determinista (tipo + recurso): repetir el mismo evento produce la misma
 * clave y, por tanto, ninguna entrega adicional.
 */
public record NotificationEvent(NotificationType type, String eventKey, NotificationResourceType resourceType,
                                UUID resourceId, String title, String message) {

    public NotificationEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
    }

    public static NotificationEvent of(NotificationType type, NotificationResourceType resourceType, UUID resourceId,
                                       String title, String message) {
        return new NotificationEvent(type, type.name() + ":" + resourceId, resourceType, resourceId, title, message);
    }

    /** El mensaje puede contener el motivo literal de un rechazo: no se registra. */
    @Override
    public String toString() {
        return "NotificationEvent[type=" + type + ", resourceId=" + resourceId + "]";
    }
}
