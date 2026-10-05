package com.stockflow.support;

import com.stockflow.notification.application.EmailDeliveryException;
import com.stockflow.notification.application.EmailSender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** SMTP simulado: registra lo enviado y puede simular un servidor caído. */
public class RecordingEmailSender implements EmailSender {

    public record Sent(String recipient, String subject, String body) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public List<Sent> sent() {
        return sent;
    }

    public void reset() {
        sent.clear();
        failing = false;
    }

    @Override
    public void send(String recipient, String subject, String textBody) {
        if (failing) {
            throw new EmailDeliveryException("SMTP down (simulated)", new RuntimeException("connection refused"));
        }
        sent.add(new Sent(recipient, subject, textBody));
    }
}
