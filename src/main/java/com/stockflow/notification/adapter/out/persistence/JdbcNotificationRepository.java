package com.stockflow.notification.adapter.out.persistence;

import com.stockflow.notification.application.NotificationRepository;
import com.stockflow.notification.domain.Notification;
import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcNotificationRepository implements NotificationRepository {

    private static final String COLUMNS =
            "id, user_id, type, title, message, resource_type, resource_id, created_at, read_at";

    private static final RowMapper<Notification> NOTIFICATION = (rs, row) -> {
        OffsetDateTime readAt = rs.getObject("read_at", OffsetDateTime.class);
        return Notification.restore(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                NotificationType.valueOf(rs.getString("type")),
                rs.getString("title"),
                rs.getString("message"),
                NotificationResourceType.valueOf(rs.getString("resource_type")),
                rs.getObject("resource_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                readAt == null ? null : readAt.toInstant());
    };

    private final JdbcClient jdbc;

    JdbcNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(Notification n, String eventKey) {
        return jdbc.sql("""
                INSERT INTO notifications
                    (id, user_id, type, event_key, title, message, resource_type, resource_id, created_at)
                VALUES (:id, :userId, :type, :eventKey, :title, :message, :resourceType, :resourceId, :createdAt)
                ON CONFLICT (user_id, event_key) DO NOTHING
                """)
                .param("id", n.id())
                .param("userId", n.userId())
                .param("type", n.type().name())
                .param("eventKey", eventKey)
                .param("title", n.title())
                .param("message", n.message())
                .param("resourceType", n.resourceType().name())
                .param("resourceId", n.resourceId())
                .param("createdAt", utc(n.createdAt()))
                .update() > 0;
    }

    @Override
    public List<Notification> findByUser(UUID userId, Boolean read, int size, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM notifications WHERE user_id = :userId" + readFilter(read)
                        + " ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("userId", userId)
                .param("size", size)
                .param("offset", offset)
                .query(NOTIFICATION)
                .list();
    }

    @Override
    public long countByUser(UUID userId, Boolean read) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :userId" + readFilter(read))
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    @Override
    public Optional<Notification> markRead(UUID userId, UUID notificationId, Instant now) {
        // Una sola sentencia: el filtro por usuario va en el UPDATE y COALESCE conserva el read_at original.
        return jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, GREATEST(:now, created_at))"
                        + " WHERE id = :id AND user_id = :userId RETURNING " + COLUMNS)
                .param("id", notificationId)
                .param("userId", userId)
                .param("now", utc(now))
                .query(NOTIFICATION)
                .optional();
    }

    /** Fragmentos fijos (nunca entrada del cliente): solo se elige entre tres constantes. */
    private static String readFilter(Boolean read) {
        if (read == null) {
            return "";
        }
        return read ? " AND read_at IS NOT NULL" : " AND read_at IS NULL";
    }
}
