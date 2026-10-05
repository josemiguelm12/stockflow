package com.stockflow.notification.adapter.out.persistence;

import com.stockflow.notification.application.EncryptedPayload;
import com.stockflow.notification.application.OutboundEmailRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcOutboundEmailRepository implements OutboundEmailRepository {

    private static final String CLAIM = """
            SELECT id, recipient_email, template_key, payload_ciphertext, payload_iv, payload_key_id
            FROM outbound_emails
            WHERE state = 'PENDING'%s
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
    public Optional<PendingEmail> claimNextPending(Collection<UUID> excludedIds) {
        boolean exclude = !excludedIds.isEmpty();
        var statement = jdbc.sql(CLAIM.formatted(exclude ? " AND id NOT IN (:excluded)" : ""));
        if (exclude) {
            statement = statement.param("excluded", excludedIds);
        }
        return statement
                .query((rs, row) -> new PendingEmail(
                        rs.getObject("id", UUID.class),
                        rs.getString("recipient_email"),
                        rs.getString("template_key"),
                        new EncryptedPayload(
                                rs.getBytes("payload_ciphertext"),
                                rs.getBytes("payload_iv"),
                                rs.getString("payload_key_id"))))
                .optional();
    }

    @Override
    public void markSent(UUID id, Instant sentAt) {
        jdbc.sql("""
                UPDATE outbound_emails
                SET state = 'SENT', sent_at = :sentAt,
                    payload_ciphertext = NULL, payload_iv = NULL, payload_key_id = NULL
                WHERE id = :id AND state = 'PENDING'
                """)
                .param("id", id)
                .param("sentAt", utc(sentAt))
                .update();
    }
}
