package com.stockflow.notification.adapter.in.web;

import com.stockflow.notification.application.ListOutboundEmails;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** RF-NOT-11: consulta administrativa del outbox. SecurityConfig exige el rol ADMIN (403 STANDARD, 401 anónimo). */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/admin/outbound-emails")
class OutboundEmailAdminController {

    private final ListOutboundEmails listOutboundEmails;

    OutboundEmailAdminController(ListOutboundEmails listOutboundEmails) {
        this.listOutboundEmails = listOutboundEmails;
    }

    @GetMapping
    OutboundEmailPageResponse list(@RequestParam(required = false) String state,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return OutboundEmailPageResponse.from(listOutboundEmails.list(state, page, size));
    }
}
