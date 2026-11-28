package com.stockflow.notification.application;

import com.stockflow.identity.application.InvalidInputException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** RF-NOT-07: preferencia de correo funcional del propio usuario. No afecta activación ni recuperación. */
@Service
public class NotificationPreferences {

    private final NotificationPreferenceRepository preferences;
    private final Clock clock;

    NotificationPreferences(NotificationPreferenceRepository preferences, Clock clock) {
        this.preferences = preferences;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isFunctionalEmailEnabled(UUID actorId) {
        return preferences.isFunctionalEmailEnabled(actorId);
    }

    @Transactional
    public boolean updateFunctionalEmail(UUID actorId, Boolean enabled) {
        if (enabled == null) {
            throw new InvalidInputException("functionalEmailEnabled", "is required");
        }
        preferences.upsert(actorId, enabled, clock.instant());
        return enabled;
    }
}
