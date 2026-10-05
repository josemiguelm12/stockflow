package com.stockflow.identity.adapter.out.persistence;

import com.stockflow.identity.application.PasswordResetTokenRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final OneTimeTokenTable table;

    JdbcPasswordResetTokenRepository(JdbcClient jdbc) {
        this.table = new OneTimeTokenTable(jdbc, "PASSWORD_RESET");
    }

    @Override
    public void insert(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        table.insert(id, userId, tokenHash, expiresAt);
    }

    @Override
    public Optional<UUID> findOwner(String tokenHash) {
        return table.findOwner(tokenHash);
    }

    @Override
    public Optional<UUID> consume(String tokenHash, Instant now) {
        return table.consume(tokenHash, now);
    }

    @Override
    public void invalidatePending(UUID userId, Instant now) {
        table.invalidatePending(userId, now);
    }
}
