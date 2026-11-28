package com.stockflow.notification.adapter.in.web;

import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.notification.application.ListNotifications;
import com.stockflow.notification.application.MarkNotificationRead;
import com.stockflow.notification.application.NotificationPreferences;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Notificaciones internas del usuario autenticado (RF-NOT-04, 05 y 07). SecurityConfig exige Bearer; el usuario sale
 * siempre de la sesión: ningún parámetro ni cuerpo aporta userId, y un parámetro desconocido se ignora.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final ListNotifications listNotifications;
    private final MarkNotificationRead markNotificationRead;
    private final NotificationPreferences preferences;

    NotificationController(ListNotifications listNotifications, MarkNotificationRead markNotificationRead,
                           NotificationPreferences preferences) {
        this.listNotifications = listNotifications;
        this.markNotificationRead = markNotificationRead;
        this.preferences = preferences;
    }

    @GetMapping
    NotificationPageResponse list(Authentication authentication,
                                  @RequestParam(required = false) Boolean read,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return NotificationPageResponse.from(listNotifications.list(actor(authentication), read, page, size));
    }

    @PostMapping("/{id}/read")
    NotificationResponse markRead(Authentication authentication, @PathVariable UUID id) {
        return NotificationResponse.from(markNotificationRead.markRead(actor(authentication), id));
    }

    @GetMapping("/preferences")
    NotificationPreferencesResponse getPreferences(Authentication authentication) {
        return new NotificationPreferencesResponse(preferences.isFunctionalEmailEnabled(actor(authentication)));
    }

    @PatchMapping("/preferences")
    NotificationPreferencesResponse updatePreferences(Authentication authentication,
                                                      @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return new NotificationPreferencesResponse(
                preferences.updateFunctionalEmail(actor(authentication), request.functionalEmailEnabled()));
    }

    private static UUID actor(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }
}
