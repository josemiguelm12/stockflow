package com.stockflow.notification.application;

import com.stockflow.identity.application.InvalidInputException;
import com.stockflow.notification.domain.Notification;
import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;
import com.stockflow.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Reglas de aplicación del motor de notificaciones con fakes en memoria (sin Spring ni base de datos). */
class NotificationUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final UUID ADMIN_1 = UUID.fromString("00000000-0000-0000-0000-00000000a101");
    private static final UUID ADMIN_2 = UUID.fromString("00000000-0000-0000-0000-00000000a102");
    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-00000000a201");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-00000000a202");
    private static final UUID RESOURCE = UUID.fromString("00000000-0000-0000-0000-00000000b101");

    private final MutableClock clock = new MutableClock(NOW);
    private final FakeNotifications notifications = new FakeNotifications();
    private final FunctionalEmailOutbox outbox = mock(FunctionalEmailOutbox.class);
    private final PublishNotification publish = new PublishNotification(notifications, outbox, clock);

    private static NotificationRecipient recipient(UUID id, boolean emailEnabled) {
        return new NotificationRecipient(id, id + "@example.test", emailEnabled);
    }

    private static NotificationEvent event() {
        return NotificationEvent.of(NotificationType.PERMISSION_REQUEST_CREATED,
                NotificationResourceType.PERMISSION_REQUEST, RESOURCE, "Nueva solicitud", "Mensaje");
    }

    // ---- PublishNotification (RF-NOT-01/06/07, RF-NOT-12) ----

    @Test
    void everyRecipientGetsAnInternalNotificationAndAnEmailWhenEnabled() {
        publish.publish(List.of(recipient(ADMIN_1, true), recipient(ADMIN_2, true)), event());

        assertThat(notifications.rows.values()).extracting(Notification::userId).containsExactlyInAnyOrder(ADMIN_1, ADMIN_2);
        verify(outbox).enqueue(recipient(ADMIN_1, true), event());
        verify(outbox).enqueue(recipient(ADMIN_2, true), event());
    }

    @Test
    void aDisabledFunctionalEmailKeepsTheInternalNotificationButQueuesNothing() {
        publish.publish(List.of(recipient(USER_A, false)), event());

        assertThat(notifications.rows).hasSize(1);
        verifyNoInteractions(outbox);
    }

    @Test
    void aRepeatedRecipientIsDeliveredOnce() {
        publish.publish(List.of(recipient(ADMIN_1, true), recipient(ADMIN_1, true), recipient(ADMIN_2, false)),
                event());

        assertThat(notifications.rows).hasSize(2);
        verify(outbox).enqueue(recipient(ADMIN_1, true), event());
        verify(outbox, never()).enqueue(recipient(ADMIN_2, false), event());
    }

    @Test
    void republishingTheSameEventCreatesNeitherANotificationNorAnEmail() {
        publish.publish(List.of(recipient(USER_A, true)), event());
        publish.publish(List.of(recipient(USER_A, true)), event());

        assertThat(notifications.rows).hasSize(1);
        verify(outbox, org.mockito.Mockito.times(1)).enqueue(any(), any());
    }

    @Test
    void theSameResourceWithAnotherEventTypeIsADistinctNotification() {
        publish.publish(List.of(recipient(USER_A, true)), event());
        publish.publish(List.of(recipient(USER_A, true)), NotificationEvent.of(
                NotificationType.PERMISSION_REQUEST_APPROVED, NotificationResourceType.PERMISSION_REQUEST, RESOURCE,
                "Aprobada", "Mensaje"));

        assertThat(notifications.rows).hasSize(2);
    }

    @Test
    void noRecipientsMeansNoEffects() {
        publish.publish(List.of(), event());

        assertThat(notifications.rows).isEmpty();
        verifyNoInteractions(outbox);
    }

    @Test
    void theNotificationCarriesTheEventDataAndTheClockInstant() {
        publish.publish(List.of(recipient(USER_A, true)), event());

        Notification n = notifications.rows.values().iterator().next();
        assertThat(n.type()).isEqualTo(NotificationType.PERMISSION_REQUEST_CREATED);
        assertThat(n.resourceType()).isEqualTo(NotificationResourceType.PERMISSION_REQUEST);
        assertThat(n.resourceId()).isEqualTo(RESOURCE);
        assertThat(n.title()).isEqualTo("Nueva solicitud");
        assertThat(n.createdAt()).isEqualTo(NOW);
        assertThat(n.isRead()).isFalse();
    }

    @Test
    void anOutboxFailurePropagatesSoTheSurroundingTransactionRollsBack() {
        doThrow(new IllegalStateException("outbox failure (simulated)")).when(outbox).enqueue(any(), any());

        assertThatThrownBy(() -> publish.publish(List.of(recipient(USER_A, true)), event()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theEventToStringHidesTheMessage() {
        NotificationEvent e = NotificationEvent.of(NotificationType.PERMISSION_REQUEST_REJECTED,
                NotificationResourceType.PERMISSION_REQUEST, RESOURCE, "t", "motivo-confidencial");

        assertThat(e.toString()).doesNotContain("motivo-confidencial");
        assertThat(recipient(USER_A, true).toString()).doesNotContain("example.test");
    }

    // ---- ListNotifications (RF-NOT-04) ----

    @Test
    void listingOnlyReturnsTheCallersNotificationsFilteredByReadState() {
        FakeNotifications repo = new FakeNotifications();
        ListNotifications list = new ListNotifications(repo);
        Notification a1 = repo.seed(USER_A, NOW.minusSeconds(30), null);
        Notification a2 = repo.seed(USER_A, NOW.minusSeconds(20), NOW);
        repo.seed(USER_B, NOW.minusSeconds(10), null);
        repo.seed(USER_B, NOW.minusSeconds(5), NOW);

        assertThat(list.list(USER_A, null, 0, 20).items()).containsExactly(a2, a1);
        assertThat(list.list(USER_A, true, 0, 20).items()).containsExactly(a2);
        assertThat(list.list(USER_A, false, 0, 20).items()).containsExactly(a1);
        assertThat(list.list(USER_A, null, 0, 20).totalElements()).isEqualTo(2);
        assertThat(list.list(USER_B, false, 0, 20).items()).extracting(Notification::userId).containsOnly(USER_B);
    }

    @Test
    void listingPaginatesWithStableOrderAndTotals() {
        FakeNotifications repo = new FakeNotifications();
        ListNotifications list = new ListNotifications(repo);
        for (int i = 0; i < 5; i++) {
            repo.seed(USER_A, NOW.minusSeconds(100 - i), null);
        }

        ListNotifications.NotificationPage second = list.list(USER_A, null, 1, 2);

        assertThat(second.items()).hasSize(2);
        assertThat(second.totalElements()).isEqualTo(5);
        assertThat(second.totalPages()).isEqualTo(3);
        assertThat(list.list(USER_A, null, 2, 2).items()).hasSize(1);
        assertThat(list.list(USER_B, null, 0, 2).totalPages()).isZero();
    }

    @Test
    void invalidPagingIsRejectedBeforeAnyQuery() {
        NotificationRepository repo = mock(NotificationRepository.class);
        ListNotifications list = new ListNotifications(repo);

        assertThatThrownBy(() -> list.list(USER_A, null, -1, 20)).isInstanceOf(InvalidInputException.class)
                .extracting(e -> ((InvalidInputException) e).field()).isEqualTo("page");
        assertThatThrownBy(() -> list.list(USER_A, null, 0, 0)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> list.list(USER_A, null, 0, 101)).isInstanceOf(InvalidInputException.class)
                .extracting(e -> ((InvalidInputException) e).field()).isEqualTo("size");

        verifyNoInteractions(repo);
    }

    // ---- MarkNotificationRead (RF-NOT-05) ----

    @Test
    void markingReadStoresReadAtOnceAndRepeatingKeepsIt() {
        MarkNotificationRead mark = new MarkNotificationRead(notifications, clock);
        Notification own = notifications.seed(USER_A, NOW.minusSeconds(60), null);

        Notification first = mark.markRead(USER_A, own.id());
        clock.advance(Duration.ofHours(1));
        Notification second = mark.markRead(USER_A, own.id());

        assertThat(first.readAt()).contains(NOW);
        assertThat(second.readAt()).as("the original readAt is kept").contains(NOW);
        assertThat(notifications.rows.get(own.id()).readAt()).contains(NOW);
    }

    @Test
    void aForeignOrUnknownNotificationIsNotFoundAndUnchanged() {
        MarkNotificationRead mark = new MarkNotificationRead(notifications, clock);
        Notification foreign = notifications.seed(USER_B, NOW.minusSeconds(60), null);

        assertThatThrownBy(() -> mark.markRead(USER_A, foreign.id())).isInstanceOf(NotificationNotFoundException.class);
        assertThatThrownBy(() -> mark.markRead(USER_A, UUID.randomUUID()))
                .isInstanceOf(NotificationNotFoundException.class);

        assertThat(notifications.rows.get(foreign.id()).isRead()).isFalse();
    }

    // ---- NotificationPreferences (RF-NOT-07) ----

    @Test
    void preferencesDefaultToEnabledAndCanBeSwitchedPerUser() {
        FakePreferences preferences = new FakePreferences();
        NotificationPreferences service = new NotificationPreferences(preferences, clock);

        assertThat(service.isFunctionalEmailEnabled(USER_A)).as("default").isTrue();
        assertThat(service.updateFunctionalEmail(USER_A, false)).isFalse();

        assertThat(service.isFunctionalEmailEnabled(USER_A)).isFalse();
        assertThat(service.isFunctionalEmailEnabled(USER_B)).as("other users are unaffected").isTrue();
        assertThat(service.updateFunctionalEmail(USER_A, true)).isTrue();
        assertThat(service.isFunctionalEmailEnabled(USER_A)).isTrue();
        assertThat(preferences.updatedAt.get(USER_A)).isEqualTo(NOW);
    }

    @Test
    void aMissingPreferenceValueIsRejected() {
        FakePreferences preferences = new FakePreferences();
        NotificationPreferences service = new NotificationPreferences(preferences, clock);

        assertThatThrownBy(() -> service.updateFunctionalEmail(USER_A, null)).isInstanceOf(InvalidInputException.class);

        assertThat(preferences.enabled).isEmpty();
    }

    // ---- fakes ----

    private static final class FakeNotifications implements NotificationRepository {

        private final Map<UUID, Notification> rows = new HashMap<>();
        private final Map<String, UUID> keys = new HashMap<>();

        Notification seed(UUID userId, Instant createdAt, Instant readAt) {
            Notification n = Notification.restore(UUID.randomUUID(), userId, NotificationType.PURCHASE_ORDER_APPROVED,
                    "t", "m", NotificationResourceType.PURCHASE_ORDER, UUID.randomUUID(), createdAt, readAt);
            rows.put(n.id(), n);
            return n;
        }

        @Override
        public boolean insertIfAbsent(Notification notification, String eventKey) {
            String key = notification.userId() + "|" + eventKey;
            if (keys.containsKey(key)) {
                return false;
            }
            keys.put(key, notification.id());
            rows.put(notification.id(), notification);
            return true;
        }

        private List<Notification> matching(UUID userId, Boolean read) {
            return rows.values().stream()
                    .filter(n -> n.userId().equals(userId))
                    .filter(n -> read == null || n.isRead() == read)
                    .sorted((x, y) -> y.createdAt().compareTo(x.createdAt()))
                    .toList();
        }

        @Override
        public List<Notification> findByUser(UUID userId, Boolean read, int size, long offset) {
            List<Notification> all = new ArrayList<>(matching(userId, read));
            int from = (int) Math.min(offset, all.size());
            return all.subList(from, Math.min(all.size(), from + size));
        }

        @Override
        public long countByUser(UUID userId, Boolean read) {
            return matching(userId, read).size();
        }

        @Override
        public Optional<Notification> markRead(UUID userId, UUID notificationId, Instant now) {
            Notification n = rows.get(notificationId);
            if (n == null || !n.userId().equals(userId)) {
                return Optional.empty();
            }
            n.markRead(now);
            return Optional.of(n);
        }
    }

    private static final class FakePreferences implements NotificationPreferenceRepository {

        private final Map<UUID, Boolean> enabled = new HashMap<>();
        private final Map<UUID, Instant> updatedAt = new HashMap<>();

        @Override
        public boolean isFunctionalEmailEnabled(UUID userId) {
            return enabled.getOrDefault(userId, true);
        }

        @Override
        public void upsert(UUID userId, boolean functionalEmailEnabled, Instant now) {
            enabled.put(userId, functionalEmailEnabled);
            updatedAt.put(userId, now);
        }
    }
}
