package com.stockflow.notification.adapter.in.web;

import jakarta.validation.constraints.NotNull;

/** Solo la preferencia: el usuario sale siempre de la sesión. */
record UpdateNotificationPreferencesRequest(@NotNull Boolean functionalEmailEnabled) {
}
