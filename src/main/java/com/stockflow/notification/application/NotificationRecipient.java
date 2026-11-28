package com.stockflow.notification.application;

import java.util.UUID;

/** Usuario ACTIVE destinatario de un aviso, con su preferencia de correo funcional ya resuelta (por defecto true). */
public record NotificationRecipient(UUID userId, String email, boolean functionalEmailEnabled) {

    @Override
    public String toString() {
        return "NotificationRecipient[userId=" + userId + ", functionalEmailEnabled=" + functionalEmailEnabled + "]";
    }
}
