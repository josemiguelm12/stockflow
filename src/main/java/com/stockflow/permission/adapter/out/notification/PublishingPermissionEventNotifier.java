package com.stockflow.permission.adapter.out.notification;

import com.stockflow.notification.application.NotificationEvent;
import com.stockflow.notification.application.NotificationRecipient;
import com.stockflow.notification.application.PublishNotification;
import com.stockflow.notification.application.RecipientDirectory;
import com.stockflow.notification.domain.Notification;
import com.stockflow.notification.domain.NotificationResourceType;
import com.stockflow.notification.domain.NotificationType;
import com.stockflow.permission.application.PermissionEventNotifier;
import com.stockflow.permission.domain.PermissionRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
class PublishingPermissionEventNotifier implements PermissionEventNotifier {

    private static final String REASON_PREFIX = "Motivo: ";

    private final PublishNotification publisher;
    private final RecipientDirectory directory;

    PublishingPermissionEventNotifier(PublishNotification publisher, RecipientDirectory directory) {
        this.publisher = publisher;
        this.directory = directory;
    }

    @Override
    public void requestCreated(PermissionRequest request) {
        String requester = directory.findUser(request.requestedBy())
                .map(NotificationRecipient::email)
                .map(email -> "El usuario " + email)
                .orElse("Un usuario");
        publisher.publish(directory.activeAdmins(), NotificationEvent.of(
                NotificationType.PERMISSION_REQUEST_CREATED, NotificationResourceType.PERMISSION_REQUEST,
                request.id(), "Nueva solicitud de permiso",
                requester + " solicitó el permiso " + request.permissionCode().name() + "."));
    }

    @Override
    public void requestApproved(PermissionRequest request) {
        publisher.publish(requesterOf(request), NotificationEvent.of(
                NotificationType.PERMISSION_REQUEST_APPROVED, NotificationResourceType.PERMISSION_REQUEST,
                request.id(), "Solicitud de permiso aprobada",
                "Su solicitud del permiso " + request.permissionCode().name() + " fue aprobada."));
    }

    @Override
    public void requestRejected(PermissionRequest request, String reason) {
        String base = "Su solicitud del permiso " + request.permissionCode().name() + " fue rechazada. "
                + REASON_PREFIX;
        // El motivo se guarda literal en la solicitud; solo el texto del aviso se recorta si no cabe.
        String message = base + Notification.truncate(reason, Notification.MAX_MESSAGE_LENGTH - base.length());
        publisher.publish(requesterOf(request), NotificationEvent.of(
                NotificationType.PERMISSION_REQUEST_REJECTED, NotificationResourceType.PERMISSION_REQUEST,
                request.id(), "Solicitud de permiso rechazada", message));
    }

    private List<NotificationRecipient> requesterOf(PermissionRequest request) {
        Optional<NotificationRecipient> requester = directory.findUser(request.requestedBy());
        return requester.map(List::of).orElse(List.of());
    }
}
