package com.stockflow.report.adapter.in.web;

import com.stockflow.report.application.ReportQueries.RequestGroup;
import com.stockflow.report.application.ReportResult;

import java.time.Instant;
import java.util.List;

/** Solo agregados: conteo por período y estado; sin ids ni detalles de solicitudes. */
record PermissionRequestReportResponse(Instant from, Instant to, String granularity, String scope,
                                       List<Group> groups) {

    record Group(Instant periodStart, String status, long count) {
    }

    static PermissionRequestReportResponse from(ReportResult<RequestGroup> result) {
        return new PermissionRequestReportResponse(result.from(), result.to(), result.granularity().name(),
                result.scope(),
                result.groups().stream().map(g -> new Group(g.periodStart(), g.status(), g.count())).toList());
    }
}
