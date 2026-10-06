package com.stockflow.identity.adapter.out.persistence;

import com.stockflow.identity.application.EmailAlreadyRegisteredException;
import com.stockflow.identity.application.LoginAccount;
import com.stockflow.identity.application.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcUserRepository implements UserRepository {

    private final JdbcClient jdbc;

    JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertPending(UUID id, String emailNormalized, String passwordHash, Instant now) {
        try {
            jdbc.sql("""
                    INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
                    VALUES (:id, :email, :hash, 'STANDARD', 'PENDING_ACTIVATION', :now, :now)
                    """)
                    .param("id", id)
                    .param("email", emailNormalized)
                    .param("hash", passwordHash)
                    .param("now", utc(now))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new EmailAlreadyRegisteredException();
        }
    }

    @Override
    public Optional<UUID> lockPendingIdByEmail(String emailNormalized) {
        return jdbc.sql("""
                SELECT id FROM users
                WHERE email_normalized = :email AND account_status = 'PENDING_ACTIVATION'
                FOR UPDATE
                """)
                .param("email", emailNormalized)
                .query(UUID.class)
                .optional();
    }

    @Override
    public void lockById(UUID userId) {
        jdbc.sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
                .param("id", userId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<LoginAccount> lockForLogin(String emailNormalized) {
        return jdbc.sql("""
                SELECT id, password_hash, account_status, failed_login_attempts, locked_until, password_reset_required
                FROM users WHERE email_normalized = :email FOR UPDATE
                """)
                .param("email", emailNormalized)
                .query((rs, row) -> {
                    OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
                    return new LoginAccount(
                            rs.getObject("id", UUID.class),
                            rs.getString("password_hash"),
                            rs.getString("account_status"),
                            rs.getInt("failed_login_attempts"),
                            lockedUntil == null ? null : lockedUntil.toInstant(),
                            rs.getBoolean("password_reset_required"));
                })
                .optional();
    }

    @Override
    public void recordFailedLogin(UUID userId, int failedAttempts, Instant lockedUntil, Instant now) {
        jdbc.sql("""
                UPDATE users SET failed_login_attempts = :attempts, locked_until = :lockedUntil, updated_at = :now
                WHERE id = :id
                """)
                .param("id", userId)
                .param("attempts", failedAttempts)
                .param("lockedUntil", lockedUntil == null ? null : utc(lockedUntil), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", utc(now))
                .update();
    }

    @Override
    public void clearLoginFailures(UUID userId, Instant now) {
        jdbc.sql("""
                UPDATE users SET failed_login_attempts = 0, locked_until = NULL, updated_at = :now WHERE id = :id
                """)
                .param("id", userId)
                .param("now", utc(now))
                .update();
    }

    @Override
    public Optional<UUID> lockActiveIdByEmail(String emailNormalized) {
        return jdbc.sql("""
                SELECT id FROM users
                WHERE email_normalized = :email AND account_status = 'ACTIVE'
                FOR UPDATE
                """)
                .param("email", emailNormalized)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<String> lockActivePasswordHash(UUID userId) {
        return jdbc.sql("SELECT password_hash FROM users WHERE id = :id AND account_status = 'ACTIVE' FOR UPDATE")
                .param("id", userId)
                .query(String.class)
                .optional();
    }

    @Override
    public void updatePassword(UUID userId, String passwordHash, Instant now) {
        jdbc.sql("""
                UPDATE users SET password_hash = :hash, password_reset_required = false, updated_at = :now
                WHERE id = :id
                """)
                .param("id", userId)
                .param("hash", passwordHash)
                .param("now", utc(now))
                .update();
    }

    @Override
    public boolean activate(UUID userId, Instant now) {
        return jdbc.sql("""
                UPDATE users
                SET account_status = 'ACTIVE', activation_completed_at = :now, updated_at = :now
                WHERE id = :id AND account_status = 'PENDING_ACTIVATION'
                """)
                .param("id", userId)
                .param("now", utc(now))
                .update() == 1;
    }
}
