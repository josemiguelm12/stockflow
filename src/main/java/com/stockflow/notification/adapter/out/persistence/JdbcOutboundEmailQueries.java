package com.stockflow.notification.adapter.out.persistence;

import com.stockflow.notification.application.OutboundEmailQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Solo columnas de metadatos: nunca se selecciona el payload cifrado. */
@Repository
class JdbcOutboundEmailQueries implements OutboundEmailQueries {

    private static final String WHERE = " WHERE (CAST(:state AS varchar) IS NULL OR state = :state)";

    private final JdbcClient jdbc;

    JdbcOutboundEmailQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long count(String state) {
        return jdbc.sql("SELECT count(*) FROM outbound_emails" + WHERE)
                .param("state", state, java.sql.Types.VARCHAR)
                .query(Long.class).single();
    }

    @Override
    public List<OutboundEmailView> page(String state, int limit, long offset) {
        return jdbc.sql("""
                SELECT id, recipient_email, template_key, state, attempt_count, enqueued_at, last_attempt_at,
                       next_attempt_at, sent_at, failed_at, last_error
                FROM outbound_emails
                """ + WHERE + """

                ORDER BY enqueued_at DESC, id
                LIMIT :limit OFFSET :offset
                """)
                .param("state", state, java.sql.Types.VARCHAR)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, row) -> new OutboundEmailView(
                        rs.getObject("id", UUID.class),
                        rs.getString("recipient_email"),
                        rs.getString("template_key"),
                        rs.getString("state"),
                        rs.getInt("attempt_count"),
                        instant(rs.getObject("enqueued_at", OffsetDateTime.class)),
                        instant(rs.getObject("last_attempt_at", OffsetDateTime.class)),
                        instant(rs.getObject("next_attempt_at", OffsetDateTime.class)),
                        instant(rs.getObject("sent_at", OffsetDateTime.class)),
                        instant(rs.getObject("failed_at", OffsetDateTime.class)),
                        rs.getString("last_error")))
                .list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
