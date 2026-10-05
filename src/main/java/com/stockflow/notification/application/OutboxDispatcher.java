package com.stockflow.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Envía los correos PENDING. Cada correo se procesa en su propia transacción: se reclama con
 * FOR UPDATE SKIP LOCKED, se envía y se marca SENT. Si el envío falla, la transacción no cambia
 * nada y el correo sigue PENDING. Límite documentado: si el proceso cae entre la aceptación SMTP y
 * el commit, el correo puede reenviarse (no se promete exactly-once).
 */
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    public record Result(int sent, int failed) {
    }

    private enum Outcome { NONE, SENT, FAILED }

    private final JsonMapper json = JsonMapper.builder().build();
    private final OutboundEmailRepository emails;
    private final OutboxPayloadCipher cipher;
    private final EmailTemplates templates;
    private final EmailSender sender;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public OutboxDispatcher(OutboundEmailRepository emails, OutboxPayloadCipher cipher, EmailTemplates templates,
                            EmailSender sender, PlatformTransactionManager transactionManager, Clock clock) {
        this.emails = emails;
        this.cipher = cipher;
        this.templates = templates;
        this.sender = sender;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Result dispatchPending(int maxEmails) {
        Set<UUID> failedIds = new HashSet<>();
        int sent = 0;
        for (int i = 0; i < maxEmails; i++) {
            Outcome outcome = transaction.execute(status -> processNext(failedIds));
            if (outcome == Outcome.NONE) {
                break;
            }
            if (outcome == Outcome.SENT) {
                sent++;
            }
        }
        return new Result(sent, failedIds.size());
    }

    private Outcome processNext(Set<UUID> failedIds) {
        Optional<OutboundEmailRepository.PendingEmail> next = emails.claimNextPending(failedIds);
        if (next.isEmpty()) {
            return Outcome.NONE;
        }
        OutboundEmailRepository.PendingEmail email = next.get();
        try {
            ActivationPayload payload = json.readValue(cipher.decrypt(email.payload()), ActivationPayload.class);
            var rendered = templates.render(email.templateKey(), payload.token());
            sender.send(email.recipient(), rendered.subject(), rendered.body());
        } catch (RuntimeException e) {
            // Solo id y tipo de error: el mensaje podría incluir direcciones o fragmentos del enlace.
            log.warn("Outbound email {} stays PENDING: {}", email.id(), e.getClass().getSimpleName());
            failedIds.add(email.id());
            return Outcome.FAILED;
        }
        emails.markSent(email.id(), clock.instant());
        log.info("Outbound email {} sent", email.id());
        return Outcome.SENT;
    }
}
