package com.stockflow.notification.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

@Service
public class ActivationEmailOutbox {

    static final String TEMPLATE_ACTIVATION = "ACTIVATION";

    private final JsonMapper json = JsonMapper.builder().build();
    private final OutboxPayloadCipher cipher;
    private final OutboundEmailRepository emails;
    private final Clock clock;

    ActivationEmailOutbox(OutboxPayloadCipher cipher, OutboundEmailRepository emails, Clock clock) {
        this.cipher = cipher;
        this.emails = emails;
        this.clock = clock;
    }

    /** Se une a la transacción del caso de uso (obligatoria); nunca contacta SMTP. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String recipient, String rawToken) {
        byte[] plaintext = json.writeValueAsBytes(new ActivationPayload(rawToken));
        emails.insertPending(UUID.randomUUID(), recipient, TEMPLATE_ACTIVATION, cipher.encrypt(plaintext),
                clock.instant());
    }
}
