package com.stockflow.notification.domain;

/** Invariante de dominio violada al construir una notificación. */
public class InvalidNotificationException extends RuntimeException {

    public InvalidNotificationException(String message) {
        super(message);
    }
}
