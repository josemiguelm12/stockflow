package com.stockflow.report.adapter.in.web;

import com.stockflow.report.application.ReportQueries.ApprovalGroup;
import com.stockflow.report.application.ReportResult;

import java.time.Instant;
import java.util.List;

/** Duraciones SUBMITTED → APPROVED en segundos, por período de aprobación; sin ids de órdenes. */
record ApprovalEfficiencyReportResponse(Instant from, Instant to, String granularity, String scope, String unit,
                                        List<Group> groups) {

    record Group(Instant periodStart, long approvedCount, double averageSeconds, double minSeconds,
                 double maxSeconds) {
    }

    static ApprovalEfficiencyReportResponse from(ReportResult<ApprovalGroup> result) {
        return new ApprovalEfficiencyReportResponse(result.from(), result.to(), result.granularity().name(),
                result.scope(), "SECONDS",
                result.groups().stream().map(g -> new Group(g.periodStart(), g.approvedCount(), g.averageSeconds(),
                        g.minSeconds(), g.maxSeconds())).toList());
    }
}
