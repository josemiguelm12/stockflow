package com.stockflow.notification;

import com.stockflow.notification.application.NotificationEvent;
import com.stockflow.notification.application.NotificationPreferenceRepository;
import com.stockflow.notification.application.NotificationRecipient;
import com.stockflow.notification.application.NotificationRepository;
import com.stockflow.notification.application.OutboundEmailRepository;
import com.stockflow.notification.application.PublishNotification;
import com.stockflow.notification.application.RecipientDirectory;
import com.stockflow.notification.application.EncryptedPayload;
import com.stockflow.notification.domain.Notification;
import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;
import com.stockflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V5 sobre V1–V4, restricciones, repositorios JDBC, aislamiento por usuario e idempotencia concurrente. */
class NotificationPersistenceIT extends AbstractPostgresIT {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NotificationRepository notifications;
    @Autowired
    private NotificationPreferenceRepository preferences;
    @Autowired
    private RecipientDirectory directory;
    @Autowired
    private OutboundEmailRepository emails;
    @Autowired
    private PublishNotification publish;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID insertUser(String role, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                  password_reset_required, failed_login_attempts, locked_until, created_at, updated_at)
                VALUES (?, ?, 'hash', ?, ?, now(), false, 0, NULL, now(), now())
                """, id, id + "@example.test", role, status);
        return id;
    }

    private Notification notification(UUID userId, Instant createdAt) {
        return Notification.create(UUID.randomUUID(), userId, NotificationType.PURCHASE_ORDER_APPROVED, "Titulo",
                "Mensaje", NotificationResourceType.PURCHASE_ORDER, UUID.randomUUID(), createdAt);
    }

    private static EncryptedPayload payload() {
        return new EncryptedPayload(new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, "test-key-1");
    }

    // ---- esquema V5 ----

    @Test
    void v5IsAppliedAndV1ToV4AreIntact() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '5'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IN ('1','2','3','4')"
                + " AND success ORDER BY version", String.class)).containsExactly("1", "2", "3", "4");
    }

    @Test
    void notificationsHaveTheContractedColumnsAndIndexes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'notifications' ORDER BY ordinal_position
                """);
        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("id", "user_id", "type", "event_key",
                "title", "message", "resource_type", "resource_id", "created_at", "read_at");
        assertThat(columns).filteredOn(c -> c.get("is_nullable").equals("YES")).extracting(c -> c.get("column_name"))
                .containsExactly("read_at");
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'notifications'",
                String.class)).contains("ix_notifications_user_created", "ix_notifications_user_unread",
                "uq_notifications_user_event");
    }

    @Test
    void orderColumnsAreNullableAndOutboxKeepsItsLegacyRowsEligible() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'purchase_orders' AND is_nullable = 'YES'
                  AND column_name IN ('owner_user_id', 'submitted_at', 'approved_at')
                """, String.class)).hasSize(3);
        UUID legacy = UUID.randomUUID();
        // Fila con la forma anterior a V5: ninguna columna nueva informada.
        jdbc.update("INSERT INTO outbound_emails (id, recipient_email, template_key, payload_ciphertext, payload_iv,"
                + " payload_key_id, state, enqueued_at) VALUES (?, 'a@example.test', 'ACTIVATION', ?, ?, 'k',"
                + " 'PENDING', now())", legacy, new byte[]{1}, new byte[]{2});

        Map<String, Object> row = jdbc.queryForMap("SELECT attempt_count, last_attempt_at, next_attempt_at, failed_at,"
                + " last_error, dedupe_key FROM outbound_emails WHERE id = ?", legacy);

        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("last_attempt_at")).isNull();
        assertThat(row.get("next_attempt_at")).as("eligible immediately").isNull();
        assertThat(row.get("failed_at")).isNull();
        assertThat(row.get("last_error")).isNull();
        assertThat(row.get("dedupe_key")).isNull();
    }

    @Test
    void outboxConstraintsAdmitFailedOnlyWithItsTimestampAndBoundTheAttempts() {
        UUID id = UUID.randomUUID();
        String insert = "INSERT INTO outbound_emails (id, recipient_email, template_key, state, enqueued_at,"
                + " attempt_count, failed_at) VALUES (?, 'a@example.test', 'ACTIVATION', ?, now(), ?, ?)";

        jdbc.update(insert, id, "FAILED", 3, OffsetDateTime.now());

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), "FAILED", 3, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_outbound_emails_failed");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), "FAILED", 4, OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbound_emails_attempt_count");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), "BOUNCED", 0, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_outbound_emails_state");
    }

    @Test
    void theDedupeKeyIsUniqueButOptionalSoEssentialEmailsStillRepeat() {
        String insert = "INSERT INTO outbound_emails (id, recipient_email, template_key, state, enqueued_at,"
                + " dedupe_key) VALUES (?, 'a@example.test', 'ACTIVATION', 'SENT', now(), ?)";

        jdbc.update(insert, UUID.randomUUID(), null);
        jdbc.update(insert, UUID.randomUUID(), null);
        jdbc.update(insert, UUID.randomUUID(), "event:user:EMAIL");

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), "event:user:EMAIL"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_outbound_emails_dedupe_key");
    }

    @Test
    void notificationConstraintsRejectInvalidRows() {
        UUID user = insertUser("STANDARD", "ACTIVE");
        String insert = "INSERT INTO notifications (id, user_id, type, event_key, title, message, resource_type,"
                + " resource_id, created_at, read_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        OffsetDateTime created = OffsetDateTime.parse("2026-06-01T12:00:00Z");
        UUID resource = UUID.randomUUID();

        jdbc.update(insert, UUID.randomUUID(), user, "PURCHASE_ORDER_APPROVED", "k1", "t", "m", "PURCHASE_ORDER",
                resource, created, null);

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), user, "PURCHASE_ORDER_APPROVED", "k1", "t", "m",
                "PURCHASE_ORDER", resource, created, null)).hasMessageContaining("uq_notifications_user_event");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), user, "UNKNOWN", "k2", "t", "m",
                "PURCHASE_ORDER", resource, created, null)).hasMessageContaining("ck_notifications_type");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), user, "PURCHASE_ORDER_APPROVED", "k3", "t", "m",
                "OTHER", resource, created, null)).hasMessageContaining("ck_notifications_resource_type");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), user, "PURCHASE_ORDER_APPROVED", "k4", "  ", "m",
                "PURCHASE_ORDER", resource, created, null)).hasMessageContaining("ck_notifications_title");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), user, "PURCHASE_ORDER_APPROVED", "k5", "t", " \n",
                "PURCHASE_ORDER", resource, created, null)).hasMessageContaining("ck_notifications_message");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), user, "PURCHASE_ORDER_APPROVED", "k6", "t", "m",
                "PURCHASE_ORDER", resource, created, created.minusSeconds(1)))
                .hasMessageContaining("ck_notifications_read_at");
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), UUID.randomUUID(), "PURCHASE_ORDER_APPROVED",
                "k7", "t", "m", "PURCHASE_ORDER", resource, created, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preferencesAreOnePerUserWithEnabledAsTheDefaultAndFollowTheUser() {
        UUID user = insertUser("STANDARD", "ACTIVE");
        jdbc.update("INSERT INTO user_notification_preferences (user_id, updated_at) VALUES (?, now())", user);

        assertThat(jdbc.queryForObject("SELECT functional_email_enabled FROM user_notification_preferences"
                + " WHERE user_id = ?", Boolean.class, user)).isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_notification_preferences (user_id, updated_at)"
                + " VALUES (?, now())", user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_notification_preferences (user_id, updated_at)"
                + " VALUES (?, now())", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM users WHERE id = ?", user);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notification_preferences", Long.class)).isZero();
    }

    // ---- repositorios ----

    @Test
    void insertIfAbsentIsIdempotentPerUserAndEventKey() {
        UUID a = insertUser("STANDARD", "ACTIVE");
        UUID b = insertUser("STANDARD", "ACTIVE");

        assertThat(notifications.insertIfAbsent(notification(a, NOW), "EVENT:1")).isTrue();
        assertThat(notifications.insertIfAbsent(notification(a, NOW), "EVENT:1")).isFalse();
        assertThat(notifications.insertIfAbsent(notification(b, NOW), "EVENT:1")).as("another user").isTrue();
        assertThat(notifications.insertIfAbsent(notification(a, NOW), "EVENT:2")).as("another event").isTrue();

        assertThat(notifications.countByUser(a, null)).isEqualTo(2);
        assertThat(notifications.countByUser(b, null)).isEqualTo(1);
    }

    @Test
    void usersOnlySeeTheirOwnNotificationsWithStableOrderAndReadFilters() {
        UUID a = insertUser("STANDARD", "ACTIVE");
        UUID b = insertUser("STANDARD", "ACTIVE");
        Notification a1 = notification(a, NOW.minusSeconds(30));
        Notification a2 = notification(a, NOW.minusSeconds(20));
        Notification a3 = notification(a, NOW.minusSeconds(10));
        Notification b1 = notification(b, NOW.minusSeconds(5));
        notifications.insertIfAbsent(a1, "a1");
        notifications.insertIfAbsent(a2, "a2");
        notifications.insertIfAbsent(a3, "a3");
        notifications.insertIfAbsent(b1, "b1");
        notifications.markRead(a, a2.id(), NOW);

        assertThat(notifications.findByUser(a, null, 10, 0)).extracting(Notification::id)
                .containsExactly(a3.id(), a2.id(), a1.id());
        assertThat(notifications.findByUser(a, true, 10, 0)).extracting(Notification::id).containsExactly(a2.id());
        assertThat(notifications.findByUser(a, false, 10, 0)).extracting(Notification::id)
                .containsExactly(a3.id(), a1.id());
        assertThat(notifications.findByUser(a, null, 1, 1)).extracting(Notification::id).containsExactly(a2.id());
        assertThat(notifications.countByUser(a, false)).isEqualTo(2);
        assertThat(notifications.findByUser(b, null, 10, 0)).extracting(Notification::id).containsExactly(b1.id());
    }

    @Test
    void markReadFixesReadAtOnceAndNeverTouchesAForeignNotification() {
        UUID owner = insertUser("STANDARD", "ACTIVE");
        UUID intruder = insertUser("STANDARD", "ACTIVE");
        Notification n = notification(owner, NOW.minusSeconds(60));
        notifications.insertIfAbsent(n, "k");

        assertThat(notifications.markRead(intruder, n.id(), NOW)).isEmpty();
        assertThat(notifications.markRead(owner, UUID.randomUUID(), NOW)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?", OffsetDateTime.class, n.id()))
                .isNull();

        Notification first = notifications.markRead(owner, n.id(), NOW).orElseThrow();
        Notification second = notifications.markRead(owner, n.id(), NOW.plusSeconds(3600)).orElseThrow();

        assertThat(first.readAt()).contains(NOW);
        assertThat(second.readAt()).as("repetition keeps the original instant").contains(NOW);
        assertThat(second.isRead()).isTrue();
    }

    @Test
    void preferencesDefaultToEnabledAndUpsertPerUser() {
        UUID a = insertUser("STANDARD", "ACTIVE");
        UUID b = insertUser("STANDARD", "ACTIVE");

        assertThat(preferences.isFunctionalEmailEnabled(a)).isTrue();
        preferences.upsert(a, false, NOW);
        assertThat(preferences.isFunctionalEmailEnabled(a)).isFalse();
        assertThat(preferences.isFunctionalEmailEnabled(b)).isTrue();
        preferences.upsert(a, true, NOW.plusSeconds(1));
        assertThat(preferences.isFunctionalEmailEnabled(a)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notification_preferences WHERE user_id = ?",
                Long.class, a)).isOne();
    }

    @Test
    void theDirectoryListsOnlyActiveAdminsButFindsAnyExistingUserById() {
        UUID activeAdmin = insertUser("ADMIN", "ACTIVE");
        UUID mutedAdmin = insertUser("ADMIN", "ACTIVE");
        UUID disabledAdmin = insertUser("ADMIN", "DISABLED");
        UUID pendingAdmin = insertUser("ADMIN", "PENDING_ACTIVATION");
        UUID standard = insertUser("STANDARD", "ACTIVE");
        UUID disabledStandard = insertUser("STANDARD", "DISABLED");
        preferences.upsert(mutedAdmin, false, NOW);

        List<NotificationRecipient> admins = directory.activeAdmins();

        assertThat(admins).extracting(NotificationRecipient::userId).containsExactlyInAnyOrder(activeAdmin, mutedAdmin);
        assertThat(admins).filteredOn(r -> r.userId().equals(activeAdmin)).singleElement()
                .satisfies(r -> assertThat(r.functionalEmailEnabled()).isTrue());
        assertThat(admins).filteredOn(r -> r.userId().equals(mutedAdmin)).singleElement()
                .satisfies(r -> assertThat(r.functionalEmailEnabled()).isFalse());
        // La búsqueda por identificador (solicitante/propietario) no filtra por estado: RF-NOT-02/03.
        assertThat(directory.findUser(standard)).isPresent();
        assertThat(directory.findUser(disabledStandard)).isPresent();
        assertThat(directory.findUser(disabledAdmin)).isPresent();
        assertThat(directory.findUser(pendingAdmin)).isPresent();
        assertThat(directory.findUser(UUID.randomUUID())).isEmpty();
        preferences.upsert(disabledStandard, false, NOW);
        assertThat(directory.findUser(disabledStandard)).get()
                .satisfies(r -> assertThat(r.functionalEmailEnabled()).as("preference still honoured").isFalse());
    }

    @Test
    void insertPendingIfAbsentIsIdempotentByDedupeKey() {
        UUID first = UUID.randomUUID();

        assertThat(emails.insertPendingIfAbsent(first, "a@example.test", "PURCHASE_ORDER_APPROVED", payload(), NOW,
                "PURCHASE_ORDER_APPROVED:1:u:EMAIL")).isTrue();
        assertThat(emails.insertPendingIfAbsent(UUID.randomUUID(), "a@example.test", "PURCHASE_ORDER_APPROVED",
                payload(), NOW, "PURCHASE_ORDER_APPROVED:1:u:EMAIL")).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbound_emails WHERE dedupe_key = ?", Long.class,
                "PURCHASE_ORDER_APPROVED:1:u:EMAIL")).isOne();
        assertThat(jdbc.queryForObject("SELECT id FROM outbound_emails WHERE dedupe_key = ?", UUID.class,
                "PURCHASE_ORDER_APPROVED:1:u:EMAIL")).isEqualTo(first);
    }

    @Test
    void aTerminalEmailKeepsItsKeySoTheEventIsNeverQueuedAgain() {
        jdbc.update("INSERT INTO outbound_emails (id, recipient_email, template_key, state, enqueued_at, sent_at,"
                + " dedupe_key) VALUES (?, 'a@example.test', 'PURCHASE_ORDER_APPROVED', 'SENT', now(), now(), 'k:EMAIL')",
                UUID.randomUUID());
        jdbc.update("INSERT INTO outbound_emails (id, recipient_email, template_key, state, enqueued_at, failed_at,"
                + " attempt_count, dedupe_key) VALUES (?, 'b@example.test', 'PURCHASE_ORDER_APPROVED', 'FAILED', now(),"
                + " now(), 3, 'f:EMAIL')", UUID.randomUUID());

        assertThat(emails.insertPendingIfAbsent(UUID.randomUUID(), "a@example.test", "PURCHASE_ORDER_APPROVED",
                payload(), NOW, "k:EMAIL")).isFalse();
        assertThat(emails.insertPendingIfAbsent(UUID.randomUUID(), "b@example.test", "PURCHASE_ORDER_APPROVED",
                payload(), NOW, "f:EMAIL")).isFalse();
    }

    // ---- idempotencia concurrente y rollback ----

    @Test
    void concurrentPublicationsOfTheSameEventCreateOneNoticeAndOneEmailPerRecipient() throws Exception {
        UUID user = insertUser("STANDARD", "ACTIVE");
        NotificationRecipient recipient = new NotificationRecipient(user, user + "@example.test", true);
        NotificationEvent event = NotificationEvent.of(NotificationType.PURCHASE_ORDER_APPROVED,
                NotificationResourceType.PURCHASE_ORDER, UUID.randomUUID(), "Orden aprobada", "Su orden fue aprobada.");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(tx -> publish.publish(List.of(recipient), event));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Long.class, user))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbound_emails WHERE recipient_email = ?", Long.class,
                user + "@example.test")).isOne();
    }

    @Test
    void publishingOutsideATransactionIsRefused() {
        UUID user = insertUser("STANDARD", "ACTIVE");
        NotificationEvent event = NotificationEvent.of(NotificationType.PURCHASE_ORDER_APPROVED,
                NotificationResourceType.PURCHASE_ORDER, UUID.randomUUID(), "t", "m");

        assertThatThrownBy(() -> publish.publish(List.of(new NotificationRecipient(user, "x@example.test", true)), event))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
    }

    @Test
    void aFailureAfterThePublicationRollsBackTheNoticeAndTheEmailTogether() {
        UUID user = insertUser("STANDARD", "ACTIVE");
        NotificationRecipient recipient = new NotificationRecipient(user, user + "@example.test", true);
        NotificationEvent event = NotificationEvent.of(NotificationType.PURCHASE_ORDER_APPROVED,
                NotificationResourceType.PURCHASE_ORDER, UUID.randomUUID(), "t", "m");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            publish.publish(List.of(recipient), event);
            throw new IllegalStateException("boom after publishing");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbound_emails", Long.class)).isZero();
    }
}
