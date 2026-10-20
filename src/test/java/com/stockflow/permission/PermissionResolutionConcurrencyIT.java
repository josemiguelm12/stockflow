package com.stockflow.permission;

import com.stockflow.permission.application.ApprovePermissionRequest;
import com.stockflow.permission.application.RejectPermissionRequest;
import com.stockflow.permission.domain.InvalidPermissionRequestTransitionException;
import com.stockflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.stockflow.shared.persistence.Timestamps.utc;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RF-GP-06 / RD-09: las decisiones sobre una misma solicitud se serializan con {@code SELECT ... FOR UPDATE}. Los
 * casos deterministas sostienen el bloqueo de la fila en una transacción, comprueban en pg_stat_activity que ambas
 * decisiones esperan de verdad y solo entonces lo liberan.
 */
class PermissionResolutionConcurrencyIT extends AbstractPostgresIT {

    private static final UUID REQUESTER = UUID.fromString("00000000-0000-0000-0000-00000000a601");
    private static final UUID FIRST_ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a602");
    private static final UUID SECOND_ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000a603");
    private static final List<UUID> USERS = List.of(REQUESTER, FIRST_ADMIN, SECOND_ADMIN);
    private static final List<UUID> ADMINS = List.of(FIRST_ADMIN, SECOND_ADMIN);
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000b601");
    // Anterior al reloj de prueba, que empieza en 2026-01-01 y solo avanza.
    private static final Instant REQUESTED_AT = Instant.parse("2025-12-01T09:00:00Z");
    private static final String REASON = "Not needed for the current role";
    private static final String OK = "OK";

    private static final String INSERT_USER = """
            INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
            VALUES (?, ?, 'hash-placeholder', ?, 'ACTIVE', now(), now())
            """;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApprovePermissionRequest approve;
    @Autowired
    private RejectPermissionRequest reject;

    /** Otras IT borran {@code users}; las FK RESTRICT exigen limpiar los permisos antes y después. */
    @BeforeEach
    void prepare() {
        deleteRows();
        jdbc.update(INSERT_USER, REQUESTER, "perm-conc-requester@example.test", "STANDARD");
        jdbc.update(INSERT_USER, FIRST_ADMIN, "perm-conc-admin-1@example.test", "ADMIN");
        jdbc.update(INSERT_USER, SECOND_ADMIN, "perm-conc-admin-2@example.test", "ADMIN");
        jdbc.update("""
                INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at)
                VALUES (?, ?, 'PURCHASE_ORDER_CREATE', 'PENDING', ?)
                """, REQUEST_ID, REQUESTER, utc(REQUESTED_AT));
    }

    @AfterEach
    void cleanUp() {
        deleteRows();
    }

    private void deleteRows() {
        jdbc.update("DELETE FROM user_permissions");
        jdbc.update("DELETE FROM permission_requests");
        for (UUID user : USERS) {
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void twoAdminsApprovingBehindTheRowLockApplyExactlyOnce() throws Exception {
        // Arrange
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes;
        try {
            // Act
            List<Future<String>> approvals = new TransactionTemplate(transactionManager).execute(status -> {
                lockRequestRow();
                Future<String> first = pool.submit(() -> attempt(() -> approve.approve(FIRST_ADMIN, REQUEST_ID)));
                Future<String> second = pool.submit(() -> attempt(() -> approve.approve(SECOND_ADMIN, REQUEST_ID)));
                awaitLockWaiters(2);
                return List.of(first, second);
            });
            outcomes = results(approvals);
        } finally {
            pool.shutdownNow();
        }

        // Assert
        assertThat(outcomes).containsExactlyInAnyOrder(OK, InvalidPermissionRequestTransitionException.class.getName());
        UUID winner = outcomes.get(0).equals(OK) ? FIRST_ADMIN : SECOND_ADMIN;
        assertAppliedOnceBy(winner);
    }

    @Test
    void anApprovalAndARejectionBehindTheRowLockLeaveOneConsistentDecision() throws Exception {
        // Arrange
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes;
        try {
            // Act
            List<Future<String>> decisions = new TransactionTemplate(transactionManager).execute(status -> {
                lockRequestRow();
                Future<String> approval = pool.submit(() -> attempt(() -> approve.approve(FIRST_ADMIN, REQUEST_ID)));
                Future<String> rejection =
                        pool.submit(() -> attempt(() -> reject.reject(SECOND_ADMIN, REQUEST_ID, REASON)));
                awaitLockWaiters(2);
                return List.of(approval, rejection);
            });
            outcomes = results(decisions);
        } finally {
            pool.shutdownNow();
        }

        // Assert
        assertThat(outcomes).containsExactlyInAnyOrder(OK, InvalidPermissionRequestTransitionException.class.getName());
        Map<String, Object> row = requestRow();
        if (outcomes.get(0).equals(OK)) {
            assertAppliedOnceBy(FIRST_ADMIN);
        } else {
            assertThat(row.get("status")).isEqualTo("REJECTED");
            assertThat(row.get("decided_by")).isEqualTo(SECOND_ADMIN);
            assertThat(row.get("rejection_reason")).isEqualTo(REASON);
            assertThat(row.get("applied_by")).isNull();
            assertThat(count("SELECT count(*) FROM user_permissions")).isZero();
        }
    }

    @Test
    void manySimultaneousApprovalsGrantThePermissionOnlyOnce() throws Exception {
        // Arrange
        List<Callable<String>> approvals = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID admin = ADMINS.get(i % 2);
            approvals.add(() -> attempt(() -> approve.approve(admin, REQUEST_ID)));
        }

        // Act
        List<String> outcomes = runConcurrently(approvals);

        // Assert
        assertThat(outcomes).filteredOn(OK::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals(OK))
                .hasSize(7)
                .containsOnly(InvalidPermissionRequestTransitionException.class.getName());
        UUID winner = ADMINS.get(outcomes.indexOf(OK) % 2);
        assertAppliedOnceBy(winner);
    }

    // ---- utilidades ----

    private void assertAppliedOnceBy(UUID winner) {
        Map<String, Object> row = requestRow();
        assertThat(row.get("status")).isEqualTo("APPLIED");
        assertThat(row.get("decided_by")).isEqualTo(winner);
        assertThat(row.get("applied_by")).isEqualTo(winner);
        assertThat(row.get("decided_at")).isEqualTo(row.get("applied_at"));
        List<Map<String, Object>> grants = jdbc.queryForList("SELECT * FROM user_permissions");
        assertThat(grants).singleElement().satisfies(grant -> {
            assertThat(grant.get("user_id")).isEqualTo(REQUESTER);
            assertThat(grant.get("source_request_id")).isEqualTo(REQUEST_ID);
            assertThat(grant.get("granted_at")).isEqualTo(row.get("applied_at"));
        });
    }

    /** {@code OK} o el nombre de la excepción: la aserción decide, no el hilo. */
    private static String attempt(Runnable decision) {
        try {
            decision.run();
            return OK;
        } catch (RuntimeException e) {
            return e.getClass().getName();
        }
    }

    private void lockRequestRow() {
        jdbc.queryForObject("SELECT id FROM permission_requests WHERE id = ? FOR UPDATE", UUID.class, REQUEST_ID);
    }

    /** Espera a que haya transacciones bloqueadas por un bloqueo de fila; si no las hay, no hay serialización. */
    private void awaitLockWaiters(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (System.nanoTime() < deadline) {
                long waiting = count("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock'");
                if (waiting >= expected) {
                    return;
                }
                Thread.sleep(50);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("Expected " + expected + " transaction(s) waiting on the request row lock");
    }

    private static List<String> results(List<Future<String>> futures) throws Exception {
        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        return results;
    }

    private <T> List<T> runConcurrently(List<? extends Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    throw new AssertionError("Unexpected failure in a concurrent task", e.getCause());
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<String, Object> requestRow() {
        return jdbc.queryForMap("SELECT * FROM permission_requests WHERE id = ?", REQUEST_ID);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
