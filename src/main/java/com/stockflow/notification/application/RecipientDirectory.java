package com.stockflow.notification.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resuelve destinatarios desde la identidad persistida. */
public interface RecipientDirectory {

    /** RF-NOT-01: solo los ADMIN con cuenta ACTIVE. */
    List<NotificationRecipient> activeAdmins();

    /**
     * RF-NOT-02/03: el usuario existente (solicitante o propietario) con independencia del estado de su cuenta.
     * Recibir un aviso no concede acceso ni capacidad de iniciar sesión. La preferencia de correo se respeta.
     */
    Optional<NotificationRecipient> findUser(UUID userId);
}
