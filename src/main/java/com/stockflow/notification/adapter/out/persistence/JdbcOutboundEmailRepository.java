package com.stockflow.notification.adapter.out.persistence;

import com.stockflow.notification.application.EncryptedPayload;
import com.stockflow.notification.application.OutboundEmailRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcOutboundEmailRepository implements OutboundEmailRepository {

    private static final String CLAIM = """
            SELECT id, recipient_email, template_key, payload_ciphertext, payload_iv, payload_key_id, attempt_count
            FROM outbound_emails
            WHERE state = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY enqueued_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcClient jdbc;

    JdbcOutboundEmailRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertPending(UUID id, String recipient, String templateKey, EncryptedPayload payload, Instant enqueuedAt) {
        jdbc.sql("""
                INSERT INTO outbound_emails
                    (id, recipient_email, template_key, payload_ciphertext, payload_iv, payload_key_id, state, enqueued_at)
                VALUES (:id, :recipient, :template, :ciphertext, :iv, :keyId, 'PENDING', :enqueuedAt)
                """)
                .param("id", id)
                .param("recipient", recipient)
                .param("template", templateKey)
                .param("ciphertext", payload.ciphertext())
                .param("iv", payload.iv())
                .param("keyId", payload.keyId())
                .param("enqueuedAt", utc(enqueuedAt))
                .update();
    }

    @Override
    public boolean insertPendingIfAbsent(UUID id, String recipient, String templateKey, EncryptedPayload payload,
                                         Instant enqueuedAt, String dedupeKey) {
        return jdbc.sql("""
                INSERT INTO outbound_emails
                    (id, recipient_email, template_key, payload_ciphertext, payload_iv, payload_key_id, state,
                     enqueued_at, dedupe_key)
                VALUES (:id, :recipient, :template, :ciphertext, :iv, :keyId, 'PENDING', :enqueuedAt, :dedupeKey)
                ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
                """)
                .param("id", id)
                .param("recipient", recipient)
                .param("template", templateKey)
                .param("ciphertext", payload.ciphertext())
                .param("iv", payload.iv())
                .param("keyId", payload.keyId())
                .param("enqueuedAt", utc(enqueuedAt))
                .param("dedupeKey", dedupeKey)
                .update() > 0;
    }

    @Override
    public Optional<PendingEmail> claimNextPending(Instant now) {
        return jdbc.sql(CLAIM)
                .param("now", utc(now))
                .query((rs, row) -> new PendingEmail(
                        rs.getObject("id", UUID.class),
                        rs.getString("recipient_email"),
                        rs.getString("template_key"),
                        new EncryptedPayload(
                                rs.getBytes("payload_ciphertext"),
                                rs.getBytes("payload_iv"),
                                rs.getString("payload_key_id")),
                        rs.getInt("attempt_count")))
                .optional();
    }

    @Override
    public void markSent(UUID id, Instant sentAt) {
        jdbc.sql("""
                UPDATE outbound_emails
                SET state = 'SENT', sent_at = :sentAt, attempt_count = attempt_count + 1, last_attempt_at = :sentAt,
                    next_attempt_at = NULL,
                    payload_ciphertext = NULL, payload_iv = NULL, payload_key_id = NULL
                WHERE id = :id AND state = 'PENDING'
                """)
                .param("id", id)
                .param("sentAt", utc(sentAt))
                .update();
    }

    @Override
    public void scheduleRetry(UUID id, int attemptCount, Instant attemptedAt, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                UPDATE outbound_emails
                SET attempt_count = :count, last_attempt_at = :attemptedAt, next_attempt_at = :next, last_error = :error
                WHERE id = :id AND state = 'PENDING'
                """)
                .param("id", id)
                .param("count", attemptCount)
                .param("attemptedAt", utc(attemptedAt))
                .param("next", utc(nextAttemptAt))
                .param("error", error)
                .update();
    }

    @Override
    public void markFailed(UUID id, int attemptCount, Instant failedAt, String error) {
        jdbc.sql("""
                UPDATE outbound_emails
                SET state = 'FAILED', attempt_count = :count, last_attempt_at = :failedAt, failed_at = :failedAt,
                    next_attempt_at = NULL, last_error = :error,
                    payload_ciphertext = NULL, payload_iv = NULL, payload_key_id = NULL
                WHERE id = :id AND state = 'PENDING'
                """)
                .param("id", id)
                .param("count", attemptCount)
                .param("failedAt", utc(failedAt))
                .param("error", error)
                .update();
    }
}
