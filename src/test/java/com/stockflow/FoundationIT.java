package com.stockflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "stockflow.cors.allowed-origins=http://localhost:4200")
class FoundationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String INSERT_USER = """
            INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
            VALUES (?, ?, 'hash-placeholder', 'STANDARD', 'PENDING_ACTIVATION', now(), now())
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v1CreatesTheFourTables() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('users', 'auth_sessions', 'one_time_tokens', 'outbound_emails')
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder("users", "auth_sessions", "one_time_tokens", "outbound_emails");
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class)).isTrue();
    }

    @Test
    void emailNormalizedIsUnique() {
        jdbc.update(INSERT_USER, UUID.randomUUID(), "unique@example.test");
        assertThatThrownBy(() -> jdbc.update(INSERT_USER, UUID.randomUUID(), "unique@example.test"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sessionRequiresExistingUser() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO auth_sessions (jti, user_id, issued_at, expires_at) VALUES (?, ?, now(), now())",
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pendingOutboundEmailRequiresEncryptedPayload() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO outbound_emails (id, recipient_email, template_key, state, enqueued_at)
                VALUES (?, 'to@example.test', 'activation', 'PENDING', now())
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rowSurvivesANewConnection() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERT_USER, id, "persisted@example.test");

        try (Connection fresh = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement select = fresh.prepareStatement("SELECT email_normalized FROM users WHERE id = ?")) {
            select.setObject(1, id);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("persisted@example.test");
            }
        }
    }
}
