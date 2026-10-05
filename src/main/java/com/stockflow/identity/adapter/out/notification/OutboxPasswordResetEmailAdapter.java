package com.stockflow.identity.adapter.out.notification;

import com.stockflow.identity.application.PasswordResetEmailPort;
import com.stockflow.notification.application.PasswordResetEmailOutbox;
import org.springframework.stereotype.Component;

@Component
class OutboxPasswordResetEmailAdapter implements PasswordResetEmailPort {

    private final PasswordResetEmailOutbox outbox;

    OutboxPasswordResetEmailAdapter(PasswordResetEmailOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void queue(String recipientEmail, String rawToken) {
        outbox.enqueue(recipientEmail, rawToken);
    }
}
