package com.stockflow.notification.application;

import java.time.Instant;
import java.util.UUID;

public interface NotificationPreferenceRepository {

    /** Ausencia de fila = habilitado. */
    boolean isFunctionalEmailEnabled(UUID userId);

    void upsert(UUID userId, boolean functionalEmailEnabled, Instant now);
}
