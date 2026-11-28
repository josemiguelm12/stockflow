package com.stockflow.support;

import com.stockflow.notification.application.EmailDeliveryException;
import com.stockflow.notification.application.EmailSender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** SMTP simulado: registra lo enviado y puede simular un servidor caído. */
public class RecordingEmailSender implements EmailSender {

    public record Sent(String recipient, String subject, String body) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile boolean failing;

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public List<Sent> sent() {
        return sent;
    }

    /** Llamadas a send, exitosas o no. */
    public int attempts() {
        return attempts.get();
    }

    public void reset() {
        sent.clear();
        attempts.set(0);
        failing = false;
    }

    @Override
    public void send(String recipient, String subject, String textBody) {
        attempts.incrementAndGet();
        if (failing) {
            throw new EmailDeliveryException("SMTP down (simulated)", new RuntimeException("connection refused"));
        }
        sent.add(new Sent(recipient, subject, textBody));
    }
}
