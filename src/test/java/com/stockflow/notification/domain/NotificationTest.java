package com.stockflow.notification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-03-01T00:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID RESOURCE = UUID.randomUUID();

    private static Notification create(String title, String message) {
        return Notification.create(UUID.randomUUID(), USER, NotificationType.PURCHASE_ORDER_APPROVED, title, message,
                NotificationResourceType.PURCHASE_ORDER, RESOURCE, CREATED_AT);
    }

    @Test
    void aNewNotificationIsUnread() {
        Notification n = create("Orden aprobada", "Su orden fue aprobada.");

        assertThat(n.isRead()).isFalse();
        assertThat(n.readAt()).isEmpty();
        assertThat(n.userId()).isEqualTo(USER);
        assertThat(n.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void requiredFieldsAndTextRulesAreEnforced() {
        assertThatThrownBy(() -> create(null, "m")).isInstanceOf(InvalidNotificationException.class);
        assertThatThrownBy(() -> create("   ", "m")).isInstanceOf(InvalidNotificationException.class);
        assertThatThrownBy(() -> create("t", null)).isInstanceOf(InvalidNotificationException.class);
        assertThatThrownBy(() -> create("t", " \n\t")).isInstanceOf(InvalidNotificationException.class);
        assertThatThrownBy(() -> create("x".repeat(Notification.MAX_TITLE_LENGTH + 1), "m"))
                .isInstanceOf(InvalidNotificationException.class);
        assertThatThrownBy(() -> create("t", "x".repeat(Notification.MAX_MESSAGE_LENGTH + 1)))
                .isInstanceOf(InvalidNotificationException.class);
        assertThat(create("x".repeat(Notification.MAX_TITLE_LENGTH), "x".repeat(Notification.MAX_MESSAGE_LENGTH)))
                .isNotNull();
        assertThatThrownBy(() -> Notification.create(null, USER, NotificationType.PURCHASE_ORDER_APPROVED, "t", "m",
                NotificationResourceType.PURCHASE_ORDER, RESOURCE, CREATED_AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Notification.create(UUID.randomUUID(), null, NotificationType.PURCHASE_ORDER_APPROVED,
                "t", "m", NotificationResourceType.PURCHASE_ORDER, RESOURCE, CREATED_AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Notification.create(UUID.randomUUID(), USER, null, "t", "m",
                NotificationResourceType.PURCHASE_ORDER, RESOURCE, CREATED_AT)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void markingReadSetsReadAtOnceAndKeepsTheOriginalOnRepetition() {
        Notification n = create("t", "m");
        Instant first = CREATED_AT.plusSeconds(60);

        n.markRead(first);
        n.markRead(first.plusSeconds(3600));

        assertThat(n.isRead()).isTrue();
        assertThat(n.readAt()).contains(first);
    }

    @Test
    void readAtIsNeverBeforeCreation() {
        Notification n = create("t", "m");

        n.markRead(CREATED_AT.minusSeconds(5));

        assertThat(n.readAt()).contains(CREATED_AT);
    }

    @Test
    void restoreKeepsAReadNotificationRead() {
        Instant readAt = CREATED_AT.plusSeconds(10);
        Notification n = Notification.restore(UUID.randomUUID(), USER, NotificationType.PERMISSION_REQUEST_CREATED,
                "t", "m", NotificationResourceType.PERMISSION_REQUEST, RESOURCE, CREATED_AT, readAt);

        assertThat(n.isRead()).isTrue();
        assertThat(n.readAt()).contains(readAt);
    }

    @Test
    void truncateOnlyShortensTextThatDoesNotFit() {
        assertThat(Notification.truncate(null, 10)).isNull();
        assertThat(Notification.truncate("short", 10)).isEqualTo("short");
        String truncated = Notification.truncate("x".repeat(50), 10);
        assertThat(truncated).hasSize(10).endsWith("…");
    }

    @Test
    void toStringDoesNotExposeTheMessage() {
        assertThat(create("Titulo", "contenido-sensible").toString()).doesNotContain("contenido-sensible");
    }
}
