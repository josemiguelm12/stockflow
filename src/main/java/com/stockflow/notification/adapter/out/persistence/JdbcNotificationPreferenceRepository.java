package com.stockflow.notification.adapter.out.persistence;

import com.stockflow.notification.application.NotificationPreferenceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcNotificationPreferenceRepository implements NotificationPreferenceRepository {

    private final JdbcClient jdbc;

    JdbcNotificationPreferenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isFunctionalEmailEnabled(UUID userId) {
        return jdbc.sql("SELECT functional_email_enabled FROM user_notification_preferences WHERE user_id = :userId")
                .param("userId", userId)
                .query(Boolean.class)
                .optional()
                .orElse(true);
    }

    @Override
    public void upsert(UUID userId, boolean functionalEmailEnabled, Instant now) {
        jdbc.sql("""
                INSERT INTO user_notification_preferences (user_id, functional_email_enabled, updated_at)
                VALUES (:userId, :enabled, :now)
                ON CONFLICT (user_id) DO UPDATE
                SET functional_email_enabled = EXCLUDED.functional_email_enabled, updated_at = EXCLUDED.updated_at
                """)
                .param("userId", userId)
                .param("enabled", functionalEmailEnabled)
                .param("now", utc(now))
                .update();
    }
}
