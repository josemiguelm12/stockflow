package com.stockflow.notification.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/** Encola el correo funcional cifrado en la transacción del evento. Nunca contacta SMTP. */
@Service
public class FunctionalEmailOutbox {

    private final JsonMapper json = JsonMapper.builder().build();
    private final OutboxPayloadCipher cipher;
    private final OutboundEmailRepository emails;
    private final Clock clock;

    FunctionalEmailOutbox(OutboxPayloadCipher cipher, OutboundEmailRepository emails, Clock clock) {
        this.cipher = cipher;
        this.emails = emails;
        this.clock = clock;
    }

    /** Devuelve false si ya existía una entrega para el mismo evento, destinatario y canal. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(NotificationRecipient recipient, NotificationEvent event) {
        byte[] plaintext = json.writeValueAsBytes(new FunctionalEmailPayload(event.title(), event.message()));
        String dedupeKey = event.eventKey() + ":" + recipient.userId() + ":EMAIL";
        return emails.insertPendingIfAbsent(UUID.randomUUID(), recipient.email(), event.type().name(),
                cipher.encrypt(plaintext), clock.instant(), dedupeKey);
    }
}
