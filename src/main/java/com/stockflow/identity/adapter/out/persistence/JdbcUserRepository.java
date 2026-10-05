package com.stockflow.identity.adapter.out.persistence;

import com.stockflow.identity.application.EmailAlreadyRegisteredException;
import com.stockflow.identity.application.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
    public Optional<UUID> findPendingIdByEmail(String emailNormalized) {
        return jdbc.sql("SELECT id FROM users WHERE email_normalized = :email AND account_status = 'PENDING_ACTIVATION'")
                .param("email", emailNormalized)
                .query(UUID.class)
                .optional();
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
