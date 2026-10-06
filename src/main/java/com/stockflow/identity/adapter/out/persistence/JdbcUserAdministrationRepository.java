package com.stockflow.identity.adapter.out.persistence;

import com.stockflow.identity.application.UserAdministrationRepository;
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
class JdbcUserAdministrationRepository implements UserAdministrationRepository {

    /** Clave fija del advisory lock de pertenencia a ADMIN (valor arbitrario, exclusivo de StockFlow). */
    private static final long ADMIN_MEMBERSHIP_LOCK_KEY = 4_172_603_311_904_218_731L;

    private static final String MANAGED_COLUMNS =
            "id, email_normalized, role, account_status, activation_completed_at IS NOT NULL AS ever_activated";

    private static final RowMapper<ManagedUser> MANAGED = (rs, row) -> new ManagedUser(
            rs.getObject("id", UUID.class),
            rs.getString("email_normalized"),
            rs.getString("role"),
            rs.getString("account_status"),
            rs.getBoolean("ever_activated"));

    private final JdbcClient jdbc;

    JdbcUserAdministrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void acquireAdminMembershipLock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", ADMIN_MEMBERSHIP_LOCK_KEY)
                .query((rs, row) -> Boolean.TRUE)
                .single();
    }

    @Override
    public List<UserSummary> page(int size, long offset) {
        return jdbc.sql("""
                SELECT id, email_normalized, role, account_status, password_reset_required, created_at, updated_at
                FROM users
                ORDER BY created_at ASC, id ASC
                LIMIT :size OFFSET :offset
                """)
                .param("size", size)
                .param("offset", offset)
                .query((rs, row) -> new UserSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("email_normalized"),
                        rs.getString("role"),
                        rs.getString("account_status"),
                        rs.getBoolean("password_reset_required"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    @Override
    public long countUsers() {
        return jdbc.sql("SELECT count(*) FROM users").query(Long.class).single();
    }

    @Override
    public Optional<ManagedUser> find(UUID userId) {
        return jdbc.sql("SELECT " + MANAGED_COLUMNS + " FROM users WHERE id = :id")
                .param("id", userId)
                .query(MANAGED)
                .optional();
    }

    @Override
    public Optional<ManagedUser> lock(UUID userId) {
        return jdbc.sql("SELECT " + MANAGED_COLUMNS + " FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query(MANAGED)
                .optional();
    }

    @Override
    public Optional<ManagedUser> lockByEmail(String emailNormalized) {
        return jdbc.sql("SELECT " + MANAGED_COLUMNS + " FROM users WHERE email_normalized = :email FOR UPDATE")
                .param("email", emailNormalized)
                .query(MANAGED)
                .optional();
    }

    @Override
    public long countActiveAdmins() {
        return jdbc.sql("SELECT count(*) FROM users WHERE role = 'ADMIN' AND account_status = 'ACTIVE'")
                .query(Long.class).single();
    }

    @Override
    public long countAdmins() {
        return jdbc.sql("SELECT count(*) FROM users WHERE role = 'ADMIN'").query(Long.class).single();
    }

    @Override
    public void updateRole(UUID userId, String role, Instant now) {
        jdbc.sql("UPDATE users SET role = :role, updated_at = :now WHERE id = :id")
                .param("id", userId)
                .param("role", role)
                .param("now", utc(now))
                .update();
    }

    @Override
    public void updateStatus(UUID userId, String accountStatus, Instant now) {
        jdbc.sql("UPDATE users SET account_status = :status, updated_at = :now WHERE id = :id")
                .param("id", userId)
                .param("status", accountStatus)
                .param("now", utc(now))
                .update();
    }

    @Override
    public void requirePasswordReset(UUID userId, Instant now) {
        jdbc.sql("UPDATE users SET password_reset_required = true, updated_at = :now WHERE id = :id")
                .param("id", userId)
                .param("now", utc(now))
                .update();
    }

    @Override
    public void insertActiveAdmin(UUID id, String emailNormalized, String passwordHash, Instant now) {
        jdbc.sql("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                                   password_reset_required, failed_login_attempts, locked_until, created_at, updated_at)
                VALUES (:id, :email, :hash, 'ADMIN', 'ACTIVE', :now, false, 0, NULL, :now, :now)
                """)
                .param("id", id)
                .param("email", emailNormalized)
                .param("hash", passwordHash)
                .param("now", utc(now))
                .update();
    }

    @Override
    public void promoteToAdmin(UUID userId, String passwordHash, Instant now) {
        jdbc.sql("""
                UPDATE users
                SET role = 'ADMIN', password_hash = :hash, password_reset_required = false, updated_at = :now
                WHERE id = :id
                """)
                .param("id", userId)
                .param("hash", passwordHash)
                .param("now", utc(now))
                .update();
    }
}
