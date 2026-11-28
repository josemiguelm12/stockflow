package com.stockflow.notification.adapter.out.persistence;

import com.stockflow.notification.application.NotificationRecipient;
import com.stockflow.notification.application.RecipientDirectory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lectura de solo consulta de {@code users} tras el puerto {@link RecipientDirectory}, con la preferencia de correo
 * funcional (ausencia de fila = habilitado) en una sola consulta. El filtro ACTIVE aplica únicamente a los
 * administradores (RF-NOT-01); la búsqueda por identificador no filtra por estado de cuenta.
 */
@Repository
class JdbcRecipientDirectory implements RecipientDirectory {

    private static final String SELECT = """
            SELECT u.id, u.email_normalized, COALESCE(p.functional_email_enabled, true) AS email_enabled
            FROM users u
            LEFT JOIN user_notification_preferences p ON p.user_id = u.id
            """;

    private static final RowMapper<NotificationRecipient> RECIPIENT = (rs, row) -> new NotificationRecipient(
            rs.getObject("id", UUID.class), rs.getString("email_normalized"), rs.getBoolean("email_enabled"));

    private final JdbcClient jdbc;

    JdbcRecipientDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NotificationRecipient> activeAdmins() {
        return jdbc.sql(SELECT + " WHERE u.role = 'ADMIN' AND u.account_status = 'ACTIVE' ORDER BY u.id")
                .query(RECIPIENT).list();
    }

    @Override
    public Optional<NotificationRecipient> findUser(UUID userId) {
        return jdbc.sql(SELECT + " WHERE u.id = :id").param("id", userId).query(RECIPIENT).optional();
    }
}
