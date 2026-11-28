package com.stockflow.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Envía los correos PENDING elegibles. Cada correo se procesa en su propia transacción: se reclama con
 * FOR UPDATE SKIP LOCKED, se envía y se marca SENT. Si el envío falla se registra el intento (RF-NOT-10): el correo
 * queda PENDING con próximo intento a 1 minuto (tras el 1.er fallo) o 5 minutos (tras el 2.º), y tras el 3.er fallo
 * pasa a FAILED, terminal. Un correo SENT o FAILED no se reclama de nuevo. Límite documentado: si el proceso cae
 * entre la aceptación SMTP y el commit, el correo puede reenviarse (no se promete exactly-once).
 */
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 300;

    /** {@code failed} cuenta los intentos fallidos de esta ejecución (con reintento programado o terminales). */
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

    /** Procesa como máximo {@code maxEmails} intentos; un correo fallido no es elegible de nuevo en la misma ejecución. */
    public Result dispatchPending(int maxEmails) {
        int sent = 0;
        int failed = 0;
        for (int i = 0; i < maxEmails; i++) {
            Outcome outcome = transaction.execute(status -> processNext());
            if (outcome == Outcome.NONE) {
                break;
            }
            if (outcome == Outcome.SENT) {
                sent++;
            } else {
                failed++;
            }
        }
        return new Result(sent, failed);
    }

    private Outcome processNext() {
        Optional<OutboundEmailRepository.PendingEmail> next = emails.claimNextPending(clock.instant());
        if (next.isEmpty()) {
            return Outcome.NONE;
        }
        OutboundEmailRepository.PendingEmail email = next.get();
        try {
            byte[] plaintext = cipher.decrypt(email.payload());
            var rendered = templates.isFunctional(email.templateKey())
                    ? templates.renderFunctional(email.templateKey(),
                            json.readValue(plaintext, FunctionalEmailPayload.class))
                    : templates.render(email.templateKey(),
                            json.readValue(plaintext, ActivationPayload.class).token());
            sender.send(email.recipient(), rendered.subject(), rendered.body());
        } catch (RuntimeException e) {
            recordFailure(email, e);
            return Outcome.FAILED;
        }
        emails.markSent(email.id(), clock.instant());
        log.info("Outbound email {} sent", email.id());
        return Outcome.SENT;
    }

    private void recordFailure(OutboundEmailRepository.PendingEmail email, RuntimeException e) {
        int attempts = email.attemptCount() + 1;
        Instant now = clock.instant();
        String error = describe(e);
        Optional<Duration> delay = EmailRetryPolicy.delayAfterFailure(attempts);
        // Solo id, intento y tipo de error: el mensaje podría incluir direcciones o fragmentos del enlace.
        if (delay.isPresent()) {
            emails.scheduleRetry(email.id(), attempts, now, now.plus(delay.get()), error);
            log.warn("Outbound email {} attempt {} failed, retry scheduled: {}", email.id(), attempts, error);
        } else {
            emails.markFailed(email.id(), attempts, now, error);
            log.warn("Outbound email {} marked FAILED after {} attempts: {}", email.id(), attempts, error);
        }
    }

    /** Error sanitizado: solo nombres de clase, nunca el mensaje de la excepción (puede contener datos del correo). */
    static String describe(RuntimeException e) {
        String text;
        if (e instanceof EmailDeliveryException) {
            Throwable cause = e.getCause();
            text = "SMTP delivery failed" + (cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")");
        } else {
            text = "Email processing failed (" + e.getClass().getSimpleName() + ")";
        }
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
