package com.stockflow.notification.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

@Service
public class PasswordResetEmailOutbox {

    private final JsonMapper json = JsonMapper.builder().build();
    private final OutboxPayloadCipher cipher;
    private final OutboundEmailRepository emails;
    private final Clock clock;

    PasswordResetEmailOutbox(OutboxPayloadCipher cipher, OutboundEmailRepository emails, Clock clock) {
        this.cipher = cipher;
        this.emails = emails;
        this.clock = clock;
    }

    /** Se une a la transacción del caso de uso (obligatoria); nunca contacta SMTP ni guarda el token en claro. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String recipient, String rawToken) {
        byte[] plaintext = json.writeValueAsBytes(new ActivationPayload(rawToken));
        emails.insertPending(UUID.randomUUID(), recipient, EmailTemplates.PASSWORD_RESET, cipher.encrypt(plaintext),
                clock.instant());
    }
}
