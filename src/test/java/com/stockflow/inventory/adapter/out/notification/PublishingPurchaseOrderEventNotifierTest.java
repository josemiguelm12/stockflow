package com.stockflow.inventory.adapter.out.notification;

import com.stockflow.notification.application.NotificationEvent;
import com.stockflow.notification.application.NotificationRecipient;
import com.stockflow.notification.application.PublishNotification;
import com.stockflow.notification.application.RecipientDirectory;
import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** RF-NOT-03: el aviso de aprobación va solo al propietario activo. */
class PublishingPurchaseOrderEventNotifierTest {

    private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-00000000d101");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000d102");

    private final PublishNotification publisher = mock(PublishNotification.class);
    private final RecipientDirectory directory = mock(RecipientDirectory.class);
    private final PublishingPurchaseOrderEventNotifier notifier =
            new PublishingPurchaseOrderEventNotifier(publisher, directory);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Collection<NotificationRecipient>> recipients() {
        return ArgumentCaptor.forClass((Class) Collection.class);
    }

    @Test
    void theOwnerIsTheOnlyRecipientAndTheEventKeyIsDeterministic() {
        when(directory.findUser(OWNER)).thenReturn(Optional.of(new NotificationRecipient(OWNER, "o@example.test", true)));
        var targets = recipients();
        var event = ArgumentCaptor.forClass(NotificationEvent.class);

        notifier.orderApproved(ORDER, OWNER);

        verify(publisher).publish(targets.capture(), event.capture());
        assertThat(targets.getValue()).extracting(NotificationRecipient::userId).containsExactly(OWNER);
        assertThat(event.getValue().type()).isEqualTo(NotificationType.PURCHASE_ORDER_APPROVED);
        assertThat(event.getValue().resourceType()).isEqualTo(NotificationResourceType.PURCHASE_ORDER);
        assertThat(event.getValue().resourceId()).isEqualTo(ORDER);
        assertThat(event.getValue().eventKey()).isEqualTo("PURCHASE_ORDER_APPROVED:" + ORDER);
    }

    @Test
    void aMissingOwnerReceivesNothing() {
        when(directory.findUser(OWNER)).thenReturn(Optional.empty());
        var targets = recipients();

        notifier.orderApproved(ORDER, OWNER);

        verify(publisher).publish(targets.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(targets.getValue()).isEmpty();
    }
}
