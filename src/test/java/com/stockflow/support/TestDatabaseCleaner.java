package com.stockflow.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Resetea exclusivamente la base PostgreSQL efímera levantada por {@link SharedPostgres}.
 *
 * <p>El orden conserva las claves foráneas de V1 a V4: los hijos de documentos se borran
 * antes de documentos, y todos los dependientes de usuarios antes de usuarios.</p>
 */
public final class TestDatabaseCleaner {

    private TestDatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM financial_extraction_drafts");
        jdbc.update("DELETE FROM documents");
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM permission_requests");
        jdbc.update("DELETE FROM purchase_orders");
        jdbc.update("DELETE FROM outbound_emails");
        jdbc.update("DELETE FROM one_time_tokens");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM users");
    }
}
