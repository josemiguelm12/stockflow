package com.stockflow.permission.adapter.out.notification;

import com.stockflow.notification.application.NotificationEvent;
import com.stockflow.notification.application.NotificationRecipient;
import com.stockflow.notification.application.PublishNotification;
import com.stockflow.notification.application.RecipientDirectory;
import com.stockflow.notification.domain.Notification;
import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;
import com.stockflow.permission.domain.PermissionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.permission.domain.PermissionCode.PURCHASE_ORDER_CREATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Destinatarios y contenido de los avisos de permisos (RF-NOT-01/02), con la lista de destinatarios ya resuelta. */
class PublishingPermissionEventNotifierTest {

    private static final UUID REQUESTER = UUID.fromString("00000000-0000-0000-0000-00000000c101");
    private static final UUID ADMIN_1 = UUID.fromString("00000000-0000-0000-0000-00000000c102");
    private static final UUID ADMIN_2 = UUID.fromString("00000000-0000-0000-0000-00000000c103");
    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-00000000c1a1");

    private final PublishNotification publisher = mock(PublishNotification.class);
    private final RecipientDirectory directory = mock(RecipientDirectory.class);
    private final PublishingPermissionEventNotifier notifier = new PublishingPermissionEventNotifier(publisher, directory);
    private final PermissionRequest request = PermissionRequest.create(REQUEST_ID, REQUESTER, PURCHASE_ORDER_CREATE,
            Instant.parse("2026-04-01T09:00:00Z"));

    private static NotificationRecipient recipient(UUID id) {
        return new NotificationRecipient(id, id + "@example.test", true);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<java.util.Collection<NotificationRecipient>> recipients() {
        return ArgumentCaptor.forClass((Class) java.util.Collection.class);
    }

    @Test
    void aNewRequestIsPublishedToTheActiveAdminsOnly() {
        when(directory.activeAdmins()).thenReturn(List.of(recipient(ADMIN_1), recipient(ADMIN_2)));
        when(directory.findUser(REQUESTER)).thenReturn(Optional.of(recipient(REQUESTER)));
        var targets = recipients();
        var event = ArgumentCaptor.forClass(NotificationEvent.class);

        notifier.requestCreated(request);

        verify(publisher).publish(targets.capture(), event.capture());
        assertThat(targets.getValue()).extracting(NotificationRecipient::userId).containsExactly(ADMIN_1, ADMIN_2);
        assertThat(event.getValue().type()).isEqualTo(NotificationType.PERMISSION_REQUEST_CREATED);
        assertThat(event.getValue().resourceType()).isEqualTo(NotificationResourceType.PERMISSION_REQUEST);
        assertThat(event.getValue().resourceId()).isEqualTo(REQUEST_ID);
        assertThat(event.getValue().eventKey()).isEqualTo("PERMISSION_REQUEST_CREATED:" + REQUEST_ID);
        assertThat(event.getValue().message()).contains(REQUESTER + "@example.test").contains("PURCHASE_ORDER_CREATE");
    }

    @Test
    void aNewRequestStillNotifiesWhenTheRequesterEmailCannotBeResolved() {
        when(directory.activeAdmins()).thenReturn(List.of(recipient(ADMIN_1)));
        when(directory.findUser(REQUESTER)).thenReturn(Optional.empty());
        var event = ArgumentCaptor.forClass(NotificationEvent.class);

        notifier.requestCreated(request);

        verify(publisher).publish(any(), event.capture());
        assertThat(event.getValue().message()).startsWith("Un usuario");
    }

    @Test
    void anApprovalIsPublishedOnlyToTheRequester() {
        when(directory.findUser(REQUESTER)).thenReturn(Optional.of(recipient(REQUESTER)));
        var targets = recipients();
        var event = ArgumentCaptor.forClass(NotificationEvent.class);

        notifier.requestApproved(request);

        verify(publisher).publish(targets.capture(), event.capture());
        assertThat(targets.getValue()).extracting(NotificationRecipient::userId).containsExactly(REQUESTER);
        assertThat(event.getValue().type()).isEqualTo(NotificationType.PERMISSION_REQUEST_APPROVED);
        assertThat(event.getValue().eventKey()).isEqualTo("PERMISSION_REQUEST_APPROVED:" + REQUEST_ID);
    }

    @Test
    void aRejectionIsPublishedOnlyToTheRequesterWithTheLiteralReason() {
        when(directory.findUser(REQUESTER)).thenReturn(Optional.of(recipient(REQUESTER)));
        var targets = recipients();
        var event = ArgumentCaptor.forClass(NotificationEvent.class);
        String reason = "  Falta justificación: ver política #4  ";

        notifier.requestRejected(request, reason);

        verify(publisher).publish(targets.capture(), event.capture());
        assertThat(targets.getValue()).extracting(NotificationRecipient::userId).containsExactly(REQUESTER);
        assertThat(event.getValue().type()).isEqualTo(NotificationType.PERMISSION_REQUEST_REJECTED);
        assertThat(event.getValue().message()).endsWith(reason);
    }

    @Test
    void anOversizedReasonIsTruncatedOnlyInTheNoticeSoItStaysValid() {
        when(directory.findUser(REQUESTER)).thenReturn(Optional.of(recipient(REQUESTER)));
        var event = ArgumentCaptor.forClass(NotificationEvent.class);

        notifier.requestRejected(request, "r".repeat(5000));

        verify(publisher).publish(any(), event.capture());
        assertThat(event.getValue().message()).hasSizeLessThanOrEqualTo(Notification.MAX_MESSAGE_LENGTH);
        Notification.create(UUID.randomUUID(), REQUESTER, event.getValue().type(), event.getValue().title(),
                event.getValue().message(), event.getValue().resourceType(), REQUEST_ID, Instant.now());
    }

    @Test
    void aMissingRequesterReceivesNothing() {
        when(directory.findUser(REQUESTER)).thenReturn(Optional.empty());
        var targets = recipients();

        notifier.requestApproved(request);
        notifier.requestRejected(request, "motivo");

        verify(publisher, org.mockito.Mockito.times(2)).publish(targets.capture(), any());
        assertThat(targets.getAllValues()).allSatisfy(t -> assertThat(t).isEmpty());
    }
}
