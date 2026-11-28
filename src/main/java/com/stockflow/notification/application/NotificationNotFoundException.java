package com.stockflow.notification.application;

/** Notificación inexistente o ajena: ambas se responden igual (404) para no confirmar datos de otros usuarios. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("Notification not found");
    }
}
