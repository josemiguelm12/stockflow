package com.stockflow.notification.adapter.in.web;

import com.stockflow.notification.application.ListNotifications;

import java.util.List;

record NotificationPageResponse(List<NotificationResponse> items, int page, int size, long totalElements,
                                long totalPages) {

    static NotificationPageResponse from(ListNotifications.NotificationPage page) {
        return new NotificationPageResponse(page.items().stream().map(NotificationResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    /** Solo totales: nunca el contenido de los mensajes. */
    @Override
    public String toString() {
        return "NotificationPageResponse[page=" + page + ", size=" + size + ", totalElements=" + totalElements + "]";
    }
}
