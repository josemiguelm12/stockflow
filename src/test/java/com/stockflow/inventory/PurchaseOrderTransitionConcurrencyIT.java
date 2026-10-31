package com.stockflow.inventory;

import com.stockflow.inventory.application.ApprovePurchaseOrder;
import com.stockflow.inventory.application.CancelPurchaseOrder;
import com.stockflow.inventory.application.PurchaseOrderRepository;
import com.stockflow.inventory.application.ReceivePurchaseOrder;
import com.stockflow.inventory.application.SubmitPurchaseOrder;
import com.stockflow.inventory.domain.InvalidPurchaseOrderTransitionException;
import com.stockflow.inventory.domain.PurchaseOrderStatus;
import com.stockflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P3-F1: las transiciones sobre una misma orden se serializan con {@code SELECT ... FOR UPDATE}.
 *
 * <p>Cada caso controla sus propias conexiones y transacciones: un hilo «poseedor» abre una transacción, bloquea la
 * fila (con el repositorio o con un caso de uso real) y avisa por {@link CountDownLatch}; mantiene la transacción
 * abierta hasta que la prueba libera otro latch. El «contendiente» ejecuta su caso de uso en otra transacción cuyo
 * backend PID publica antes de empezar. La espera se demuestra solo con esos dos PID
 * ({@code pg_blocking_pids(contendiente)} contiene al poseedor), nunca con contadores globales; y que la fila no se
 * puede modificar, con un {@code UPDATE} bajo {@code lock_timeout} desde una tercera conexión, que falla con
 * {@code 55P03}. Todas las esperas tienen un máximo y los hilos y transacciones se liberan en {@code finally}.
 */
class PurchaseOrderTransitionConcurrencyIT extends AbstractPostgresIT {

    private static final UUID FIRST_ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000c601");
    private static final UUID SECOND_ADMIN = UUID.fromString("00000000-0000-0000-0000-00000000c602");
    private static final UUID STANDARD = UUID.fromString("00000000-0000-0000-0000-00000000c603");
    private static final List<UUID> USERS = List.of(FIRST_ADMIN, SECOND_ADMIN, STANDARD);
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-00000000e601");
    private static final Instant CREATED_AT = Instant.parse("2026-02-03T04:05:06Z");
    private static final String OK = "OK";
    private static final String INVALID = InvalidPurchaseOrderTransitionException.class.getName();
    private static final long TIMEOUT_SECONDS = 10;
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private static final String INSERT_USER = """
            INSERT INTO users (id, email_normalized, password_hash, role, account_status, created_at, updated_at)
            VALUES (?, ?, 'hash-placeholder', ?, 'ACTIVE', now(), now())
            """;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private PurchaseOrderRepository orders;
    @Autowired
    private SubmitPurchaseOrder submit;
    @Autowired
    private ApprovePurchaseOrder approve;
    @Autowired
    private ReceivePurchaseOrder receive;
    @Autowired
    private CancelPurchaseOrder cancel;

    private ExecutorService pool;

    @BeforeEach
    void prepare() {
        deleteRows();
        jdbc.update(INSERT_USER, FIRST_ADMIN, "po-conc-admin-1@example.test", "ADMIN");
        jdbc.update(INSERT_USER, SECOND_ADMIN, "po-conc-admin-2@example.test", "ADMIN");
        jdbc.update(INSERT_USER, STANDARD, "po-conc-standard@example.test", "STANDARD");
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("worker threads finished").isTrue();
        deleteRows();
    }

    private void deleteRows() {
        jdbc.update("DELETE FROM purchase_orders WHERE id = ?", ORDER_ID);
        for (UUID user : USERS) {
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }

    @Test
    void lockByIdReturnsTheOrderAndHoldsTheRowUntilTheTransactionEnds() throws Exception {
        // Arrange
        seed(PurchaseOrderStatus.SUBMITTED);
        RowLockHolder holder = new RowLockHolder(() -> assertThat(orders.lockById(ORDER_ID)).hasValueSatisfying(
                order -> assertThat(order.status()).isEqualTo(PurchaseOrderStatus.SUBMITTED)));
        try {
            holder.awaitLocked();

            // Act
            Throwable blockedUpdate = updateFromAnotherConnection("APPROVED");
            Contender approval = new Contender(() -> approve.approve(FIRST_ADMIN, ORDER_ID));
            approval.awaitBlockedBy(holder);
            String stateWhileLocked = state();
            holder.release();

            // Assert
            assertThat(sqlState(blockedUpdate)).as("UPDATE from another connection").isEqualTo(LOCK_NOT_AVAILABLE);
            assertThat(stateWhileLocked).isEqualTo("SUBMITTED");
            assertThat(holder.result()).isEqualTo(OK);
            assertThat(approval.result()).isEqualTo(OK);
            assertThat(state()).isEqualTo("APPROVED");
            assertThat(orders.lockById(UUID.randomUUID())).isEmpty();
        } finally {
            holder.release();
        }
    }

    @Test
    void aTransitionWaitingOnTheLockSeesTheStateCommittedByTheHolder() throws Exception {
        // Arrange — el poseedor aprueba dentro de su transacción y no confirma hasta que se le libera.
        seed(PurchaseOrderStatus.SUBMITTED);
        RowLockHolder holder = new RowLockHolder(() -> approve.approve(FIRST_ADMIN, ORDER_ID));
        try {
            holder.awaitLocked();

            // Act — recibir solo es legal desde APPROVED: si leyera SUBMITTED, la política lo rechazaría.
            Contender reception = new Contender(() -> receive.receive(SECOND_ADMIN, ORDER_ID));
            reception.awaitBlockedBy(holder);
            String stateBeforeCommit = state();
            holder.release();

            // Assert
            assertThat(stateBeforeCommit).as("uncommitted approval is not visible").isEqualTo("SUBMITTED");
            assertThat(holder.result()).isEqualTo(OK);
            assertThat(reception.result()).isEqualTo(OK);
            assertThat(state()).isEqualTo("RECEIVED");
        } finally {
            holder.release();
        }
    }

    @Test
    void twoAdminsApprovingBehindTheRowLockApproveExactlyOnce() throws Exception {
        // Arrange
        seed(PurchaseOrderStatus.SUBMITTED);
        RowLockHolder holder = new RowLockHolder(() -> approve.approve(FIRST_ADMIN, ORDER_ID));
        try {
            holder.awaitLocked();

            // Act
            Contender secondApproval = new Contender(() -> approve.approve(SECOND_ADMIN, ORDER_ID));
            secondApproval.awaitBlockedBy(holder);
            holder.release();

            // Assert — la segunda relee APPROVED y APPROVED -> APPROVED se rechaza: no hay doble aprobación.
            assertThat(holder.result()).isEqualTo(OK);
            assertThat(secondApproval.result()).isEqualTo(INVALID);
            assertThat(state()).isEqualTo("APPROVED");
        } finally {
            holder.release();
        }
    }

    @Test
    void anApprovalAndACancellationBehindTheRowLockLeaveOneConsistentState() throws Exception {
        // Arrange — la cancelación posee el bloqueo; la aprobación espera detrás.
        seed(PurchaseOrderStatus.SUBMITTED);
        RowLockHolder holder = new RowLockHolder(() -> cancel.cancel(SECOND_ADMIN, ORDER_ID));
        try {
            holder.awaitLocked();

            // Act
            Contender approval = new Contender(() -> approve.approve(FIRST_ADMIN, ORDER_ID));
            approval.awaitBlockedBy(holder);
            holder.release();

            // Assert — CANCELLED es terminal: la aprobación no sobrescribe el estado más reciente.
            assertThat(holder.result()).isEqualTo(OK);
            assertThat(approval.result()).isEqualTo(INVALID);
            assertThat(state()).isEqualTo("CANCELLED");
        } finally {
            holder.release();
        }
    }

    @Test
    void manySimultaneousSubmissionsSubmitOnlyOnce() throws Exception {
        // Arrange
        seed(PurchaseOrderStatus.DRAFT);
        List<Callable<String>> submissions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID actor = i % 2 == 0 ? STANDARD : FIRST_ADMIN;
            submissions.add(() -> attempt(() -> submit.submit(actor, ORDER_ID)));
        }

        // Act
        List<String> outcomes = runConcurrently(submissions);

        // Assert
        assertThat(outcomes).filteredOn(OK::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals(OK)).hasSize(7).containsOnly(INVALID);
        assertThat(state()).isEqualTo("SUBMITTED");
    }

    @Test
    void aTransitionRolledBackByItsTransactionLeavesTheRowUntouched() {
        // Arrange
        seed(PurchaseOrderStatus.SUBMITTED);

        // Act
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            approve.approve(FIRST_ADMIN, ORDER_ID);
            status.setRollbackOnly();
        });

        // Assert
        assertThat(state()).isEqualTo("SUBMITTED");
    }

    // ---- coordinación explícita ----

    /**
     * Abre una transacción en un hilo propio, ejecuta {@code lockingWork} (que bloquea la fila), publica su backend PID,
     * avisa con {@code locked} y no termina la transacción hasta {@link #release()} o hasta el tiempo máximo.
     */
    private final class RowLockHolder {

        private final CountDownLatch locked = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final CompletableFuture<Integer> pid = new CompletableFuture<>();
        private final Future<String> outcome;

        RowLockHolder(Runnable lockingWork) {
            outcome = pool.submit(() -> attempt(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        pid.complete(backendPid());
                        lockingWork.run();
                        locked.countDown();
                        if (!awaitQuietly(released)) {
                            throw new IllegalStateException("The test never released the row lock");
                        }
                    })));
        }

        void awaitLocked() throws Exception {
            if (!locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("The holder did not lock the row: "
                        + (outcome.isDone() ? outcome.get() : "still running"));
            }
        }

        int pid() throws Exception {
            return pid.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        String result() throws Exception {
            return outcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Ejecuta un caso de uso en su propia transacción, cuyo backend PID publica antes de llamarlo. */
    private final class Contender {

        private final CompletableFuture<Integer> pid = new CompletableFuture<>();
        private final Future<String> outcome;

        Contender(Runnable useCase) {
            outcome = pool.submit(() -> attempt(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        pid.complete(backendPid());
                        useCase.run();
                    })));
        }

        /**
         * Espera, con tiempo máximo, a que PostgreSQL informe que <em>este</em> backend está bloqueado por el backend
         * del poseedor. Mientras tanto el contendiente no puede haber terminado.
         */
        void awaitBlockedBy(RowLockHolder holder) throws Exception {
            int waiter = pid.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int blocker = holder.pid();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                assertThat(outcome).as("the contender must wait for the holder").isNotDone();
                Boolean blocked = jdbc.queryForObject("SELECT ? = ANY (pg_blocking_pids(?))", Boolean.class,
                        blocker, waiter);
                if (Boolean.TRUE.equals(blocked)) {
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
            throw new AssertionError("Backend " + waiter + " never waited on the row lock held by backend " + blocker);
        }

        String result() throws Exception {
            return outcome.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Intenta cambiar la fila desde otra conexión sin esperar más de 200 ms; devuelve el error obtenido. */
    private Throwable updateFromAnotherConnection(String state) {
        return catchThrowable(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '200ms'");
            jdbc.update("UPDATE purchase_orders SET state = ? WHERE id = ?", state, ORDER_ID);
        }));
    }

    private static String sqlState(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private Integer backendPid() {
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- utilidades ----

    private void seed(PurchaseOrderStatus status) {
        jdbc.update("INSERT INTO purchase_orders (id, supplier_id, state, created_at) VALUES (?, ?, ?, ?)",
                ORDER_ID, UUID.randomUUID(), status.name(), OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
    }

    /** {@code OK} o el nombre de la excepción: la aserción decide, no el hilo. */
    private static String attempt(Runnable transition) {
        try {
            transition.run();
            return OK;
        } catch (RuntimeException e) {
            return e.getClass().getName();
        }
    }

    private <T> List<T> runConcurrently(List<? extends Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(workers.submit(() -> {
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
            workers.shutdownNow();
        }
    }

    private String state() {
        return jdbc.queryForObject("SELECT state FROM purchase_orders WHERE id = ?", String.class, ORDER_ID);
    }
}
