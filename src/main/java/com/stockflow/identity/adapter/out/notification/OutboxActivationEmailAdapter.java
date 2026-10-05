package com.stockflow.identity.adapter.out.notification;

import com.stockflow.identity.application.ActivationEmailPort;
import com.stockflow.notification.application.ActivationEmailOutbox;
import org.springframework.stereotype.Component;

@Component
class OutboxActivationEmailAdapter implements ActivationEmailPort {

    private final ActivationEmailOutbox outbox;

    OutboxActivationEmailAdapter(ActivationEmailOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void queue(String recipientEmail, String rawToken) {
        outbox.enqueue(recipientEmail, rawToken);
    }
}
