package com.stockflow.inventory.adapter.out.notification;

import com.stockflow.inventory.application.PurchaseOrderEventNotifier;
import com.stockflow.notification.application.NotificationEvent;
import com.stockflow.notification.application.NotificationRecipient;
import com.stockflow.notification.application.PublishNotification;
import com.stockflow.notification.application.RecipientDirectory;
import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
class PublishingPurchaseOrderEventNotifier implements PurchaseOrderEventNotifier {

    private final PublishNotification publisher;
    private final RecipientDirectory directory;

    PublishingPurchaseOrderEventNotifier(PublishNotification publisher, RecipientDirectory directory) {
        this.publisher = publisher;
        this.directory = directory;
    }

    @Override
    public void orderApproved(UUID orderId, UUID ownerId) {
        List<NotificationRecipient> owner = directory.findUser(ownerId).map(List::of).orElse(List.of());
        publisher.publish(owner, NotificationEvent.of(NotificationType.PURCHASE_ORDER_APPROVED,
                NotificationResourceType.PURCHASE_ORDER, orderId, "Orden de compra aprobada",
                "Su orden de compra " + orderId + " fue aprobada."));
    }
}
