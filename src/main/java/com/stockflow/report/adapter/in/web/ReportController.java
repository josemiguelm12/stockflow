package com.stockflow.report.adapter.in.web;

import com.stockflow.identity.application.AuthenticatedUser;
import com.stockflow.report.application.PermissionRequestReport;
import com.stockflow.report.application.PurchaseOrderApprovalReport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reportes agregados (RF-REP-01…05). SecurityConfig exige Bearer; actor y rol salen de la sesión. Ningún parámetro
 * aporta userId: uno desconocido se ignora y no altera el alcance.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/reports")
class ReportController {

    private final PermissionRequestReport permissionRequests;
    private final PurchaseOrderApprovalReport approvals;

    ReportController(PermissionRequestReport permissionRequests, PurchaseOrderApprovalReport approvals) {
        this.permissionRequests = permissionRequests;
        this.approvals = approvals;
    }

    @GetMapping("/permission-requests")
    PermissionRequestReportResponse permissionRequests(Authentication authentication,
                                                       @RequestParam(required = false) String desde,
                                                       @RequestParam(required = false) String hasta,
                                                       @RequestParam(required = false) String granularity) {
        AuthenticatedUser actor = (AuthenticatedUser) authentication.getPrincipal();
        return PermissionRequestReportResponse.from(
                permissionRequests.generate(actor.userId(), actor.role(), desde, hasta, granularity));
    }

    @GetMapping("/purchase-order-approval-efficiency")
    ApprovalEfficiencyReportResponse approvalEfficiency(Authentication authentication,
                                                        @RequestParam(required = false) String desde,
                                                        @RequestParam(required = false) String hasta,
                                                        @RequestParam(required = false) String granularity) {
        AuthenticatedUser actor = (AuthenticatedUser) authentication.getPrincipal();
        return ApprovalEfficiencyReportResponse.from(
                approvals.generate(actor.userId(), actor.role(), desde, hasta, granularity));
    }
}
