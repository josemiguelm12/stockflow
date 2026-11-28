package com.stockflow.notification.adapter.in.web;

import com.stockflow.notification.application.ListOutboundEmails;

import java.util.List;

record OutboundEmailPageResponse(List<OutboundEmailResponse> items, int page, int size, long totalElements,
                                 long totalPages) {

    static OutboundEmailPageResponse from(ListOutboundEmails.OutboundEmailPage page) {
        return new OutboundEmailPageResponse(page.items().stream().map(OutboundEmailResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }

    /** Solo totales: nunca direcciones de destinatarios. */
    @Override
    public String toString() {
        return "OutboundEmailPageResponse[page=" + page + ", size=" + size + ", totalElements=" + totalElements + "]";
    }
}
