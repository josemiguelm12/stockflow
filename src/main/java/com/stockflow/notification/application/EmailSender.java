package com.stockflow.notification.application;

public interface EmailSender {

    /** Lanza EmailDeliveryException si el servidor SMTP no acepta el mensaje. */
    void send(String recipient, String subject, String textBody);
}
